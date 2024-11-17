(ns httpkit.fake-test
  (:require [clojure.test :refer :all]
            [httpkit.fake :refer :all]
            [org.httpkit.client :as http]))

(deftest test-simple-get
  (testing "Basic GET request with string URL"
    (with-fake-http {"http://example.com" {:status 200
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
    (with-fake-http {#"http://example.com/\d+" {:status 200
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
    (with-fake-http {[:post "http://example.com"] {:status 201
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
                 (with-fake-http-in-isolation {"http://example.com" {:status 200}}
                   (let [p (promise)]
                     (http/get "http://other.com" {}
                             (fn [_] (deliver p :done)))
                     @p))))))

(deftest test-dynamic-response
  (testing "Response generation using function"
    (with-fake-http {"http://example.com" (fn [req]
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
    (with-fake-http {"http://example.com" {:status 200}}
      (let [p1 (promise)
            p2 (promise)]
        (http/get "http://example.com" {}
                 (fn [_] (deliver p1 :done)))
        (http/get "http://example.com" {}
                 (fn [_] (deliver p2 :done)))
        [@p1 @p2]
        (is (= 2 (get @*call-counts* ["http://example.com" :get] 0)))))))
