(ns tumblr-archiver.core
  (:require [oauth.client :as oauth]
            [byte-streams :as bs]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clj-http.client :as http]
            [slingshot.slingshot :refer [try+]])
  (:import (com.tumblr.jumblr JumblrClient)))

; State management

(def dir
  (io/file (System/getenv "HOME") ".tumblr-archiver"))

(def state-file
  (io/file dir "state.edn"))

(defn default-archive-dir
  "Where do files go?"
  []
  (.getCanonicalPath (io/file (System/getenv "HOME") "tumblr-archive")))

(defn save-state!
  "Save state to disk."
  [state]
  (io/make-parents (.getCanonicalPath state-file))
  (with-open [w (io/writer state-file)]
    (binding [*out* w]
      (pprint @state))))

(defn default
  "Like assoc but only replaces missing values."
  [m k v]
  (if (contains? m k)
    m
    (assoc m k v)))

(defn load-state
  "Load state from disk."
  []
  (-> (try
        (with-open [f (java.io.PushbackReader. (io/reader state-file))]
          (edn/read f))
        (catch java.io.FileNotFoundException e
          {}))
      (default :creds {})
      (default :archive-dir (default-archive-dir))
      (default :archived #{})
      atom))

; Oauth workflow ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn oauth-consumer
  [creds]
  (oauth/make-consumer (:consumer-token        creds)
                       (:consumer-token-secret creds)
                       "https://www.tumblr.com/oauth/request_token"
                       "https://www.tumblr.com/oauth/access_token"
                       "https://www.tumblr.com/oauth/authorize"
                       :hmac-sha1))

(defn request-token
  [consumer]
  (oauth/request-token consumer "https://aphyr.com/tumblr/no-callback"))

(defn prompt-auth
  [consumer request-token]
  (println (oauth/user-approval-uri consumer (:oauth_token request-token)))
  (sh "xdg-open" (oauth/user-approval-uri consumer (:oauth_token request-token))))

(defn read-verifier
  "Accept token and verifier params from the CLI."
  []
  (print "Verifier: ")
  (flush)
  (read-line))

(defn auth
  "Given a map with :consumer-token and :consumer-secret, goes through the auth
  workflow. The user should end up at a url, then copy the verifier back into
  this program, which will spit out the oauth token and secret"
  [creds]
  (let [consumer      (oauth-consumer creds)
        request-token (request-token consumer)
        _             (prompt-auth consumer request-token)
        access-token  (oauth/access-token
                        consumer request-token (read-verifier))]
    access-token))

; Tumblr client ;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn client
  "Creates a client with the given keys:

      :consumer-token
      :consumer-token-secret
      :oauth-token
      :oauth-token-secret"
  [{:keys [consumer-token consumer-token-secret
           oauth-token oauth-token-secret]}]
  (doto (JumblrClient. consumer-token consumer-token-secret)
    (.setToken oauth-token oauth-token-secret)))

(defn str-map
  [m]
  (->> m
       (map (fn [[k v]] [(name k) (if (keyword? v) (name v) v)]))
       (into {})))

(defn likes
  ([client]
   (likes client {:limit 32 :offset 0}))
  ([client page]
   (lazy-seq
     (concat (.userLikes client (str-map page))
             (likes client (update page :offset + (:limit page)))))))

(defn post
  [client blog id]
  (.blogPost client blog id))

(defn url->post
  "Look up a post by url."
  [client url]
  (let [[match user id] (re-find #"https?://([^\.]+)\.tumblr\.com/post/(\d+)"
                                 url)]
    (if match
      (try (post client user (Long/parseLong id))
           (catch com.tumblr.jumblr.exceptions.JumblrException e
             (if (re-find #"Not Found" (.getMessage e))
               nil
               (throw e))))
      (throw (IllegalArgumentException. (str "Can't match " url))))))

(defn post-source
  "What post did this post reblog?"
  [post]
  (when-let [u (.getSourceUrl post)]
    (try (url->post (.getClient post) u)
         (catch IllegalArgumentException e
           ; Must have been a weird URL, like sometimes you can reblog patreon
           ; pages???
           nil))))

(defn post-chain
  "Expands a post into a sequence of posts, reaching backwards to their source."
  [post]
  (when post
    (cons post (lazy-seq (post-chain (post-source post))))))

(defn post-url
  [post]
  (.getPostUrl post))

(defn bigger-photo-url
  "Turns _500.jpg into _1280.jpg"
  [url]
  (str/replace-first url #"_500\.jpg\z" "_1280.jpg"))

(defn photo-url
  "Extracts a URL from a photo."
  [photo]
  (.. photo getOriginalSize getUrl))

(defn embed-url
  "Extracts a URL from an embed code."
  [embed]
  (->> [#"<source src=\"([^\"]+)\""
        #"data-instgrm-permalink=\"([^\"]+)\""]
       (keep (fn [pattern]
               (when-let [match (re-find pattern embed)]
                 (nth match 1))))
       first))

(defn video-url
  "Extracts a URL from a video."
  [video]
  (->> video
       .getEmbedCode
       embed-url))

(defn text-urls
  "Extract urls from text"
  [text]
  (condp instance? text
    com.tumblr.jumblr.types.AnswerPost (recur (.getAnswer text))
    com.tumblr.jumblr.types.TextPost   (recur (.getBody text))
    String (->> text
                (re-seq #"https?://[^\.]+\.(jpg|jpeg|gif|png|mp4|mp3)")
                (map first)
                (map bigger-photo-url))
    (throw (IllegalArgumentException.
             (str "Not sure how to extract text urls from "
                  (class text))))))

(defn post-media*
  "Extracts media URLs from a single post."
  [post]
  (case (.getType post)
    "photo"   (map photo-url  (.getPhotos post))
    "video"   (keep video-url (.getVideos post))
    "answer"  (text-urls post)
    "text"    (text-urls post)
    "audio"   [(.getAudioUrl post)]
    (throw (IllegalArgumentException. (str "Don't know how to archive "
                                           (.getType post) "\n"
                                           (with-out-str
                                             (pprint (bean post))))))))

(defn post-media
  "Extracts media URLs from posts and their sources."
  [post]
  (->> post
       post-chain
       (mapcat post-media*)
       distinct))

; Downloading stuff ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn distinct-file
  "Constructs a local filename for a string basename and extension."
  [base ext]
  (let [f (io/file (str base "." ext))]
    (if (.exists f)
      (recur (if-let [m (re-find #"\A(.+)-(\d+)\z" base)]
               (str (m 1) "-" (inc (Long/parseLong (m 2))))
               (str base "-1"))
             ext)
      f)))

(defn download-url!
  "Downloads a URL to the tumblr-archive directory."
  [state url]
  (println "Downloading" url)
  (try+
    (let [[m file final ext] (re-find #"/([^/.]+)(\.([^\.\/]+))?\z" url)
          [_ fallback-file]  (re-find #"\Ahttps?:\/\/(.+)\z" url)
          r (http/get url {:insecure? true
                           :as :stream})
          type (get-in r [:headers "Content-Type"])
          ext  (case type
                 "image/jpeg" "jpg"
                 "image/gif"  "gif"
                 "image/png"  "png"
                 "video/mp4"  "mp4"
                 "text/html"  "html"
                 "audio/mpeg" "mp3"
                 (throw (RuntimeException.
                          (str "Don't know extension for content type "
                               type))))
          local-file (distinct-file (.getCanonicalPath
                                      (io/file (:archive-dir @state)
                                               (or file fallback-file)))
                                    ext)]
      ; Write file
      (io/make-parents local-file)
      (with-open [w (io/output-stream local-file)]
        (bs/transfer (:body r) w)))
    (catch [:status 403] e
      (println "Forbidden"))
    (catch [:status 404] e
      (println "Not found"))))

(defn archive!
  "Given a post, archives it."
  [state post]
  (doseq [url (post-media post)]
    (download-url! state url))
  (swap! state update :archived conj (post-url post)))

(defn archived?
  "Is this post already archived?"
  [state post]
  ((:archived @state) (post-url post)))

(defn maybe-archive!
  "Archives a post if we haven't done it already, updating state when done."
  [state post]
  (if (archived? state post)
    (println "Already have " (post-url post))
    (do (println "Archiving " (post-url post))
        (archive! state post)
        (println "Archived  " (post-url post)))))

(defn go!
  []
  (let [state (load-state)]
    (try
      (let [client (client (:creds @state))]
        (doseq [post (likes client)]
          (maybe-archive! state post)))
      ; Clean up
      (sh "fdupes" "-dN" (.getCanonicalPath (:archive-dir @state)))
      (finally
        (save-state! state)))))

(defn -main [& args]
  (go!))
