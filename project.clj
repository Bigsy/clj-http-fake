(defproject org.clojars.bigsy/clj-http-fake "1.0.5"
  :description "Helper for faking clj-http requests in testing, like Ruby's fakeweb."
  :url "https://github.com/Bigsy/clj-http-fake"
  :license {:name "MIT License"
            :url  "http://www.opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/math.combinatorics "0.2.0"]
                 [robert/hooke "1.3.0"]
                 [clj-http "3.1.0"] ;; Needed here or lein might not compile clj-http first, resulting in broken builds
                 [ring/ring-codec "1.2.0"]]
  :aliases {"test-2.x" ["with-profile" "latest-2.x,1.7:latest-2.x,1.8:latest-2.x,1.9:latest-2.x,1.10:latest-2.x,1.11" "test"]
            "test-3.x" ["with-profile" "latest-3.x,1.7:latest-3.x,1.8:latest-3.x,1.9:latest-3.x,1.10:latest-3.x,1.11" "test"]}
  :profiles {:1.11 {:dependencies [[org.clojure/clojure "1.11.1"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             ;;the latest supported versions of clj-http from the 2.x and 3.x releases:
             :latest-2.x {:dependencies [[clj-http "2.3.0"]]}
             :latest-3.x {:dependencies [[clj-http "3.12.3"]]}})