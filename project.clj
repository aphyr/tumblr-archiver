(defproject tumblr-archiver "0.1.0-SNAPSHOT"
  :description "Downloads tumblr liked media to a local directory."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [clj-oauth "1.5.5"]
                 [byte-streams "0.2.3"]
                 [com.tumblr/jumblr "0.0.11"]]
  :main tumblr-archiver.core)
