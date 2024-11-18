(defproject org.clojars.bigsy/clj-http-stub "0.0.1"
  :description "Helper for faking clj-http and http-kit requests in testing"
  :url "https://github.com/Bigsy/clj-http-stub"
  :license {:name "MIT License"
            :url  "http://www.opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/math.combinatorics "0.3.0"]
                 [robert/hooke "1.3.0"]
                 [clj-http "3.13.0"]
                 [http-kit "2.7.0"]
                 [ring/ring-codec "1.2.0"]]
  :aliases {"test-3.x" ["with-profile" "clj-3.x,1.10:clj-3.x,1.11:clj-3.x,1.12" "test"]}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.12.0"]]}
             :1.12 {:dependencies [[org.clojure/clojure "1.12.0"]]}
             :1.11 {:dependencies [[org.clojure/clojure "1.11.1"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]]}

             :clj-3.x {:dependencies [[clj-http "3.13.0"]]}})