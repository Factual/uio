(ns uio.impl
  (:require [clojure.java.io :as jio]
            [clojure.string :as str])
  (:import [clojure.lang Keyword IPersistentMap IFn]
           [java.io FilterInputStream FilterOutputStream InputStream OutputStream ByteArrayOutputStream ByteArrayInputStream]
           [java.net URI URLEncoder]
           [uio.fs NullOutputStream]))

(def default-delimiter "/")
(def default-opts-ls   {:recurse false})

; URL manipulation (not really a part of public API)
;
; "foo://user@host:8080/some-dir/file.txt?arg=value" <-- URL
; "foo"                                              <-- scheme
;            "host"                                  <-- host
;                  8080                              <-- port (int or nil)
;                     "/some-dir/file.txt"           <-- path
;                      "some-dir/file.txt"           <-- path-no-slash
;
(defn ->url         ^URI     [^String url] (-> url URI.))
(defn scheme        ^Keyword [^String url] (-> url ->url .getScheme keyword))
(defn host          ^String  [^String url] (-> url ->url .getHost))
(defn port          ^String  [^String url] (let [p (-> url ->url .getPort)]
                                             (if (not= -1 p) p)))
(defn path          ^String  [^String url] (-> url ->url .getPath))
(defn path-no-slash ^String  [^String url] (-> url path (subs 1)))
(defn filename      ^String  [^String url] (let [s (-> url path)]
                                             (subs s (inc (str/last-index-of s default-delimiter)))))

(defn encode-url    ^String  [^String url] (.replace (URLEncoder/encode url "UTF-8") "+" "%20"))

; Public API
(defmulti from    (fn [^String url]        (scheme url)))   ; url         -> InputStream  -- must be closed by user, use (with-open [...])
(defmulti to      (fn [^String url]        (scheme url)))   ; url         -> OutputStream -- must be closed by user, use (with-open [...])
(defmulti size    (fn [^String url]        (scheme url)))   ; url         -> Number
(defmulti exists? (fn [^String url]        (scheme url)))   ; url         -> boolean
(defmulti delete  (fn [^String url]        (scheme url)))   ; url -> args -> nil
(defmulti ls      (fn [^String url & opts] (scheme url)))   ; url -> args -> []
(defmulti mkdir   (fn [^String url & opts] (scheme url)))   ; url -> args -> nil
(defmulti copy    (fn [^String from-url ^String to-url]     ; url url -> nil
                    (->url from-url)                        ; ensure `from-url` is also parsable
                    (scheme to-url)))                       ; dispatch on `scheme` (and ensure it's also parsable)

; Codecs
(defmulti ext->is->is (fn [^String ext] ext)) ; ext -> (fn [^InputStream  is] ...wrap into another InputStream)
(defmulti ext->os->os (fn [^String ext] ext)) ; ext -> (fn [^OutputStream os] ...wrap into another OutputStream)

; Helper fns (for implementations)

; Examples:
; (die "MUHAHA!")
; (die "Can't find keys" {:what "keys" :to "my car"})
; (die "This url is misbehaving" {:url url} ioe)
(defn die "A shortcut for (throw (ex-info ...))"
  ([^String msg]           (throw (Exception. msg)))
  ([^String msg map]       (throw (ex-info msg map)))
  ([^String msg map cause] (throw (ex-info msg map cause))))

; Example:
; (let [file "/non-existent/path/to/file.txt"]
;   (rethrowing "Couldn't write to a file" {:file file}
;     (spit file "ignore")))
;
(defmacro rethrowing
  "Evaluate body, and if any exception is thrown then wrap that
   exception into an ex-info with the specified msg and map before rethrowing it."
  [msg map & body]
  ; TODO assert msg is a String
  ; TODO assert map is a map
  `(try (do ~@body)
        (catch Exception e# (die (str ~msg ". " (.getMessage e#)) ~map e#))))

(def ^:dynamic *config* {})

(defn config [^Keyword k] (get *config* k))
(defn env    [^Keyword k] (System/getenv k))

(defn wrap-is [->resource resource->is close-resource]
  (let [r (->resource)]
    (proxy [FilterInputStream] [(resource->is r)]
      (close [] (try (proxy-super close)
                     (finally (close-resource r)))))))

(defn wrap-os [->resource resource->os close-resource]
  (let [r (->resource)
        os (resource->os r)]
    (proxy [FilterOutputStream] [os]
      ; delegate batch methods as is -- don't peel into individual (.write ... ^int) calls
      ; NOTE: this lowers chances of SFTP implementation to hang (concurrency bug in JSch)
      ;       and improves performance
      (write ([^bytes bs]               (.write os bs))
        ([^bytes bs offset length] (.write os bs offset length)))

      (close [] (try (proxy-super close)
                     (finally (close-resource r)))))))

; Example:
; (try-with #(FileInputStream. "file://1.txt")
;           #(.available %)
;           #(.close %))
; => ...returns result of `(.avaiable ...)`
;
(defn try-with [->resource resouce->value close-resource]
  (let [r (->resource)]
    (try (resouce->value r)
         (finally (close-resource r)))))

(defn get-opts [default-opts url args]                      ; {opts} -> url -> [{opts}] -> {opts}
  (if (< 1 (count args))
    (die (str "Expected 0 or 1 arguments for `opts`, but got " (count args))
         {:url url :args args}))

  ; TODO assert (first args) is a map or nil

  (merge default-opts (first args)))

; helper fns to support directories and recursive operations

; TODO rename to `ends-with-slash?`
(defn ends-with-delimiter? [url]
  (str/ends-with? (path url) default-delimiter))

(defn ensure-ends-with-delimiter [url]
  (if (ends-with-delimiter? url)
    url
    (str url default-delimiter)))

; Example:
; (get-parent-dir "/" "1/2/3.txt")
; => "1/2"
(defn parent-of                                                ; -> [String] (does not end with a slash)
  ([^String url] (parent-of default-delimiter url))
  ([^String delimiter ^String url]
   (if-let [i (str/last-index-of url delimiter)]
     (subs url 0 i)
     nil)))

(defn with-parent [^String parent-url ^String file]
  (if (str/includes? file default-delimiter)
    (die (str "Argument \"file\" expected to have no directory delimiters, but it had: " (pr-str file))))
  (str (ensure-ends-with-delimiter parent-url) file))

(defn ensure-has-no-trailing-slash [^String url]
  (if (str/ends-with? url default-delimiter)
    (recur (.substring url 0 (- (count url) 1)))
    url))

; See "test_uio.clj", (facts "intercalate-with-dirs works" ...)
; TODO consider adding few examples here
(defn intercalate-with-dirs
  ([kvs]           (intercalate-with-dirs default-delimiter kvs))
  ([delimiter kvs] (intercalate-with-dirs nil delimiter kvs))
  ([delimiter last-flushed-dir [kv & kvs]]
   (if-not kv
     []
     (let [parent-dir (parent-of (:url kv))
           flush-dir? (and parent-dir
                           (neg? (compare last-flushed-dir parent-dir)))

           tail       (lazy-seq
                        (intercalate-with-dirs delimiter
                                               (if flush-dir?
                                                 parent-dir
                                                 last-flushed-dir)
                                               kvs))]
       (cond->> (cons kv tail)
                flush-dir? (cons {:url parent-dir
                                  :dir true}))))))

; codec related fns
;
(defn ensure-has-all-impls [url ext+s->s]
  (if (not-every? (comp some? second) ext+s->s)
    (die (str "Got at least one unsupported codec. " ; TODO tell which one
              "Consider adding adding `:codecs false` option or implementing a codec for the unknown extension")
         {:unsupported (->> (remove second ext+s->s)
                            (mapv first))
          :exts        (mapv first ext+s->s)
          :url         url})
    ext+s->s))

; Based on file extensions of given url, build a sequence of pairs: extension + stream->stream codec function.
; Where `ext->s->s` is either `ext->is->is` or `ext->os->os` (depending on whether you're reading or writing)"
;
; Example (reading):
; (url->ext+s->s ext->is->is "file:///path/to/file.txt.xz.bz2.gz")
; => [[:gz  (fn [^InputStream] ...another InputStream)]]
;     [:bz2 (fn [^InputStream] ...another InputStream)]]
;     [:xz  (fn [^InputStream] ...another InputStream)]]]
;
; Example (writing):
; (url->ext+s->s ext->os->os "file:///path/to/file.txt.xz.bz2.gz")
; => [[:gz  (fn [^OutputStream] ...another OutputStream)]]
;     [:bz2 (fn [^OutputStream] ...another OutputStream)]]
;     [:xz  (fn [^OutputStream] ...another OutputStream)]]]
;
; Example (of an unknown codec in the middle):
; (url->ext+s->s ext->os->os "file:///path/to/file.txt.xz.ufoz.bz2.gz")
; =!> clojure.lang.ExceptionInfo: Got at least one unsupported codec.
;     Consider adding adding `:codecs false` option or implementing a codec for the unknown extension
;     {:unsupported [:ufoz],
;      :exts        [:gz :bz2 :ufoz :xz],
;      :url         "file:///path/to/file.txt.xz.ufoz.bz2.gz"}, compiling:
;
(defn url->ext+s->s
  [ext->s->s ^String url]
  (->> (str/split (path url) #"\.")
       (map keyword)
       (map #(vector % (ext->s->s %)))
       (drop-while (comp nil? second))
       reverse
       (ensure-has-all-impls url)))

; Example (reading):
; (apply-codecs (from "file:///path/to/file.txt.xz.gz")
;               (url->ext+s->s ext->is->is "file:///path/to/file.txt.xz.gz"))
; => InputStream ...that reads from file:///path/to/file.txt.xz.gz, decompresses with GZIP, then XZ
;
; Example (writing):
; (apply-codecs (to "file:///path/to/file.txt.xz.gz")
;               (url->ext+s->s ext->os->os "file:///path/to/file.txt.xz.gz"))
; => OuputStream ...that compresses with XZ, then GZIP and writes to file:///path/to/file.txt.xz.gz
;
(defn apply-codecs
  "Wrap given `InputStream` or `OutputStream` with codecs specified in `ext+s->s` sequence"
  [s ext+s->s]
  (reduce (fn [s [ext s->s]]
            (rethrowing (str "Problem with " ext) {:exts (mapv first ext+s->s)}
                        (s->s s)))
          s
          ext+s->s))

; streams<->bytes functions
(defn ^bytes is->bytes [^InputStream is]
  (let [baos (ByteArrayOutputStream.)]
    (jio/copy is baos)
    (.toByteArray baos)))

(defn ^InputStream bytes->is [^bytes bs]
  (ByteArrayInputStream. bs))

(defn ^bytes with-baos->bytes [^IFn baos->nil]
  (let [baos (ByteArrayOutputStream.)]
    (baos->nil baos)
    (.toByteArray baos)))

; manual encoding/decoding: streams
(defn ^InputStream  ext-decode-is [^Keyword ext ^InputStream is]  ((ext->is->is ext) is))
(defn ^OutputStream ext-encode-os [^Keyword ext ^OutputStream os] ((ext->os->os ext) os))

; manual encoding/decoding: bytes
(defn ^bytes ext-decode-bytes [^Keyword ext ^bytes source]
  (with-baos->bytes
    #(with-open [is ((ext->os->os ext) (bytes->is source))
                 os %]
       (jio/copy is os))))

(defn ^bytes ext-encode-bytes [^Keyword ext ^bytes source]
  (with-baos->bytes
    #(with-open [is (bytes->is source)
                 os ((ext->os->os ext) %)]
       (jio/copy is os))))

; other streams functions
(defn ^OutputStream nil-os []
  (NullOutputStream.))


; Rest of Public API
(defn with-fn [config f]
  (if-not (instance? IPersistentMap config)
    (die (str "Argument `config` expected to be a map, but was " (.getName (class config)))))

  (binding [*config* (merge *config* config)]
    (f)))

; TODO resolve clash with uio/with
;(defmacro with [config & body]
;  `(with-fn ~config (fn [] ~@body)))

; TODO add examples
(defn ^InputStream from* [^String url]
  (rethrowing "Couldn't apply is->is codecs" {:url url}
              (apply-codecs (from url) (url->ext+s->s ext->is->is url))))

; TODO add examples
(defn ^OutputStream to* [^String url]
  (rethrowing "Couldn't apply os->os codecs" {:url url}
              (apply-codecs (to url) (url->ext+s->s ext->os->os url))))

; Implementation: defaults
(defn default-impl [^String url ^String method args]
  (if (scheme url)
    (die "Not implemented" {:url url :method method :args args})
    (die (str "Expected a URL with a scheme, but got: \"" url "\". "
              "Available schemes are: "
              (->> method
                   symbol resolve deref methods keys
                   (remove #{:default})
                   (map #(str (name %) "://"))
                   sort
                   (str/join ", "))))))

(defmethod from    :default [url]        (default-impl url "from"    nil))
(defmethod to      :default [url]        (default-impl url "to"      nil))
(defmethod size    :default [url]        (default-impl url "size"    nil))
(defmethod exists? :default [url]        (default-impl url "exists?" nil))
(defmethod delete  :default [url]        (default-impl url "delete"  nil))
(defmethod ls      :default [url & args] (default-impl url "ls"      args))
(defmethod mkdir   :default [url & args] (default-impl url "mkdir"   args))

(defmethod copy    :default [from-url to-url] (with-open [is (from from-url)
                                                          os (to to-url)]
                                                (jio/copy is os :buffer-size 8192)))

(defmethod ext->is->is :default [_] nil)
(defmethod ext->os->os :default [_] nil)
