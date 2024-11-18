(ns httpkit.fake-test
  (:require [clojure.test :refer :all]
            [httpkit.fake :refer :all]
            [fake.shared :refer [*call-counts*]]
            [org.httpkit.client :as http]))

(deftest test-simple-get
  (testing "Basic GET request with string URL"
    (with-fake-routes {"http://example.com" {:status 200
                                          :headers {"Content-Type" "text/plain"}
                                          :body "Hello World"}}
      (let [p (promise)]
        (http/get "http://example.com" {}
                 (fn [{:keys [status headers body]}]
                   (is (= 200 status))
                   (is (= "text/plain" (get headers "Content-Type")))
                   (is (= "Hello World" body))
                   (deliver p :done)))
        @p))))

(deftest test-pattern-matching
  (testing "Pattern matching for URLs"
    (with-fake-routes {#"http://example.com/\d+" {:status 200
                                               :body "Numbered resource"}}
      (let [p (promise)]
        (http/get "http://example.com/123" {}
                 (fn [{:keys [status body]}]
                   (is (= 200 status))
                   (is (= "Numbered resource" body))
                   (deliver p :done)))
        @p))))

(deftest test-method-specific-response
  (testing "Different responses for different HTTP methods"
    (with-fake-routes {[:post "http://example.com"] {:status 201
                                                  :body "Created"}
                     [:get "http://example.com"] {:status 200
                                                :body "OK"}}
      (let [p1 (promise)
            p2 (promise)]
        (http/post "http://example.com" {}
                  (fn [{:keys [status body]}]
                    (is (= 201 status))
                    (is (= "Created" body))
                    (deliver p1 :done)))
        (http/get "http://example.com" {}
                 (fn [{:keys [status body]}]
                   (is (= 200 status))
                   (is (= "OK" body))
                   (deliver p2 :done)))
        [@p1 @p2]))))

(deftest test-isolation-mode
  (testing "Requests not matching routes throw exception in isolation mode"
    (is (thrown? Exception
                 (with-fake-routes-in-isolation {"http://example.com" {:status 200}}
                   (let [p (promise)]
                     (http/get "http://other.com" {}
                             (fn [_] (deliver p :done)))
                     @p))))))

(deftest test-dynamic-response
  (testing "Response generation using function"
    (with-fake-routes {"http://example.com" (fn [req]
                                           {:status 200
                                            :body (str "Request method was: " 
                                                     (name (:method req)))})}
      (let [p (promise)]
        (http/get "http://example.com" {}
                 (fn [{:keys [body]}]
                   (is (= "Request method was: get" body))
                   (deliver p :done)))
        @p))))

(deftest test-request-recording
  (testing "Records the number of times routes are called"
    (with-fake-routes {"http://example.com" {:status 200}}
      (let [p1 (promise)
            p2 (promise)]
        (http/get "http://example.com" {}
                 (fn [_] (deliver p1 :done)))
        (http/get "http://example.com" {}
                 (fn [_] (deliver p2 :done)))
        [@p1 @p2]
        (is (= 2 (get @*call-counts* ["http://example.com" :get] 0)))))))

(deftest test-query-params
  (testing "Empty query params"
    (with-fake-routes {#"http://example.com/api" {:status 200 :body "OK"}}
      (let [p (promise)]
        (http/get "http://example.com/api" {:query-params {}}
                 (fn [{:keys [status body]}]
                   (is (= 200 status))
                   (is (= "OK" body))
                   (deliver p :done)))
        @p)))
  
  (testing "Query param order doesn't matter"
    (with-fake-routes {"http://example.com/api?a=1&b=2" {:status 200 :body "OK"}}
      (let [p1 (promise)
            p2 (promise)]
        (http/get "http://example.com/api" {:query-params {:a "1" :b "2"}}
                 (fn [{:keys [body]}]
                   (is (= "OK" body))
                   (deliver p1 :done)))
        (http/get "http://example.com/api" {:query-params {:b "2" :a "1"}}
                 (fn [{:keys [body]}]
                   (is (= "OK" body))
                   (deliver p2 :done)))
        [@p1 @p2])))
  
  (testing "Query params in map route spec"
    (with-fake-routes {{:url "http://example.com/api"
                       :query-params {:q "test"}} 
                      {:status 200 :body "Found"}}
      (let [p (promise)]
        (http/get "http://example.com/api" {:query-params {:q "test"}}
                 (fn [{:keys [body]}]
                   (is (= "Found" body))
                   (deliver p :done)))
        @p))))

(deftest test-url-matching-edge-cases
  (testing "Default port handling"
    (with-fake-routes {"http://example.com:80/api" {:status 200 :body "OK"}}
      (let [p (promise)]
        (http/get "http://example.com/api" {}
                 (fn [{:keys [body]}]
                   (is (= "OK" body))
                   (deliver p :done)))
        @p)))
  
  (testing "Trailing slashes"
    (with-fake-routes {"http://example.com/api/" {:status 200 :body "OK"}}
      (let [p (promise)]
        (http/get "http://example.com/api" {}
                 (fn [{:keys [body]}]
                   (is (= "OK" body))
                   (deliver p :done)))
        @p)))
  
  (testing "Default scheme"
    (with-fake-routes {"example.com" {:status 200 :body "OK"}}
      (let [p (promise)]
        (http/get "http://example.com" {}
                 (fn [{:keys [body]}]
                   (is (= "OK" body))
                   (deliver p :done)))
        @p))))

(deftest test-route-matching-precedence
  (testing "Uses first matching route"
    (with-fake-routes {"http://example.com" {:status 200 :body "First"}
                      "http://example.com/" {:status 200 :body "Second"}}
      (let [p (promise)]
        (http/get "http://example.com/" {}
                 (fn [{:keys [body]}]
                   (is (= "First" body))
                   (deliver p :done)))
        @p)))
  
  (testing "Any method matching"
    (with-fake-routes {[:any "http://example.com"] {:status 200 :body "Any"}}
      (let [p1 (promise)
            p2 (promise)]
        (http/get "http://example.com" {}
                 (fn [{:keys [body]}]
                   (is (= "Any" body))
                   (deliver p1 :done)))
        (http/post "http://example.com" {}
                  (fn [{:keys [body]}]
                    (is (= "Any" body))
                    (deliver p2 :done)))
        [@p1 @p2]))))
