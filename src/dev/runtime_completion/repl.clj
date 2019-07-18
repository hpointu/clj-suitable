(ns runtime-completion.repl
  (:require [figwheel.main]
            [figwheel.main.api]
            [nrepl server core]
            [cider nrepl piggieback]
            [clojure.pprint :refer [cl-format pprint]]
            [clojure.stacktrace :refer [print-stack-trace print-trace-element]]
            [clojure.zip :as zip]
            [clojure.walk :as walk]
            [cider.piggieback]
            [runtime-completion.middleware])
  (:import (java.lang Thread)))



;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(require '[nrepl.misc :as misc :refer [response-for]])
(require '[cider.piggieback])
(require
 '[nrepl
   [middleware :refer [set-descriptor!]]
   [transport :as transport]])
(require '[cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]])
(import 'nrepl.transport.Transport)

(defn- cljs-eval
  "Abuses the nrepl handler `piggieback/do-eval` in that it injects a pseudo
  transport into it that simply captures it's output."
  [session ns code]
  (let [result (transient [])
        transport (reify Transport
                    (recv [this] this)
                    (recv [this timeout] this)
                    (send [this response] (conj! result response) this))
        eval-fn (or (resolve 'piggieback.core/do-eval)
                    (resolve 'cider.piggieback/do-eval))]
    (eval-fn {:session session :transport transport :code code :ns ns})
    (persistent! result)))

(defonce state (atom nil))

(comment
  (cljs-eval (-> @state :session) (-> @state :ns) "(+ 1 2)")
  (cljs-eval (-> @state :session) (-> @state :ns) "(+ 222 333) (+ 1 2)")
  (cljs-eval (-> @state :session) (-> @state :ns) "(ns-interns 'runtime-completion.core)")
  (cljs-eval (-> @state :session) (-> @state :ns) "(runtime-completion.core/properties-by-prototype js/console)")
  )

(defn cljs-dynamic-completion-handler
  [next-handler {:keys [id session transport op ns symbol context extra-metadata] :as msg}]


  (when (and (= op "complete")
             (some #(get-in @session [(resolve %)]) '(piggieback.core/*cljs-compiler-env*
                                                      cider.piggieback/*cljs-compiler-env*))
             )
    (cl-format true "~A: ~A~%" op (select-keys msg [:ns :symbol :context :extra-metadata]))

    (let [answer (merge (when id {:id id})
                        (when session {:session (if (instance? clojure.lang.AReference session)
                                                  (-> session meta :id)
                                                  session)}))]

      ;; (println (cljs-eval session "(properties-by-prototype js/console)" ns))
      (reset! state {:handler next-handler
                     :session session
                     :ns ns})
      (transport/send transport (assoc answer :completions [{:candidate "cljs.hello", :type "var"}]))))

  ;; call next-handler for the default completions
  (next-handler msg))


(defn wrap-cljs-dynamic-completions [handler]
  (fn [msg] (cljs-dynamic-completion-handler handler msg)))

(set-descriptor! #'wrap-cljs-dynamic-completions
                 {:requires #{"clone"}
                  :expects #{"complete" "eval"}
                  :handles {}})


;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

;; a la https://github.com/nrepl/piggieback/issues/91
;; 1. start nrepl server with piggieback
;; 2. get session
;; 3. send cljs start form (e.g. nashorn or figwheel)
;; 4. ...profit!

;; 1. start nrepl server with piggieback
(defonce server (atom nil))
(defonce send-msg (atom nil))

(defn start-nrepl-server []
  (let [middlewares (map resolve cider.nrepl/cider-middleware)
        middlewares (conj middlewares #'cider.piggieback/wrap-cljs-repl)
        middlewares (conj middlewares #'runtime-completion.middleware/wrap-cljs-dynamic-completions)
        ;; handler (nrepl.server/default-handler #'cider.piggieback/wrap-cljs-repl)
        handler (apply nrepl.server/default-handler middlewares)]
   (reset! server (nrepl.server/start-server :handler handler :port 7889)))
  (cl-format true "nrepl server started~%"))

(defonce client (atom nil))
(defonce client-session (atom nil))

(defn start-nrepl-client []
  (let [conn (nrepl.core/connect :port 7889)
        c (nrepl.core/client conn 1000)
        sess (nrepl.core/client-session c)]
    (reset! client c)
    (reset! client-session sess)
    (cl-format true "nrepl client started~%")
    (reset! send-msg
            (fn [msg] (let [response-seq (nrepl.core/message sess msg)]
                         (cl-format true "nrepl msg send~%")
                         (pprint (doall response-seq)))))))

(defn send-eval [code]
  (@send-msg {:op :eval :code code}))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(comment
  (pprint (doall (nrepl.core/message sess1 {:op :eval :code "(require 'cider.piggieback) (require 'cljs.repl.nashorn) (cider.piggieback/cljs-repl (cljs.repl.nashorn/repl-env))"})))
  (pprint (doall (nrepl.core/message sess1 {:op :eval :code "(require 'figwheel.main) (figwheel.main/start :fig)"})))
  (pprint (doall (nrepl.core/message sess1 {:op :eval :code "(require 'figwheel.main) (figwheel.main/stop-builds :fig)"})))
  (pprint (doall (nrepl.core/message sess1 {:op :eval :code ":cljs/quit"})))
  (pprint (doall (nrepl.core/message sess1 {:op :eval :code "js/console"})))
  (pprint (doall (nrepl.core/message sess1 {:op :eval :code "123"})))
  (nrepl.core/message sess1 {:op :eval :code "(list 1 2 3)"})
  )


(comment


  (do (start-nrepl-server)
      (start-nrepl-client)
      (send-eval "(require 'figwheel.main) (figwheel.main/start :fig)"))

  (do (nrepl.server/stop-server @server)
      (require 'figwheel.main.api)
      (figwheel.main.api/stop-all))

  (require 'figwheel.main.api) (figwheel.main.api/cljs-repl "fig")
  (class Transport)
  123
  (send-eval "(require 'runtime-completion.core)")
  (send-eval "123")

  (send-eval "(require 'figwheel.main.api) (figwheel.main.api/cljs-repl \"fig\")")
  (send-eval ":cljs/quit")

  (@send-msg {:op :close})

  (@send-msg {:op :complete :symbol "js/co" :ns "cljs.user" :context nil})
  (@send-msg {:op :complete :symbol "cljs." :ns "runtime-completion.core" :context nil})

  (require '[cider.nrepl.inlined-deps.cljs-tooling.v0v3v1.cljs-tooling.complete :as cljs-complete])
  (require '[cider.nrepl.middleware.util.cljs :as cljs])

  (let [ns (symbol "cljs.user")
        prefix (str "cljs.co")
        extra-metadata (set (map keyword nil))
        cljs-env (some-> (figwheel.main.api/repl-env "fig") :repl-options deref :compiler-env deref)]
    (cljs-complete/completions cljs-env prefix {:context-ns ns
                                                :extra-metadata extra-metadata}))

  ;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-



  (send-eval "123")


  (send-eval "(require 'figwheel.main) (figwheel.main/start :fig)")
  (send-eval "*ns*")
  (send-eval "js/console")


  (-> @figwheel.main/build-registry (get "fig"))
  (figwheel.main.watching/reset-watch!)

  (def server (-> @figwheel.main/build-registry (get "fig")  :repl-env :server deref))
  (.stop server)

  (figwheel.main/stop-builds :fig)

  (-> (read-string "(.log (foo (__prefix__)))") walk/prewalk-demo)
  (-> (read-string "(.log (foo (__prefix__)))") walk/postwalk-demo)
  (-> (read-string "(.log (foo (__prefix__)))") tree-seq)

  (tree-seq )

  (require '[clojure.zip :as zip])
  (require '[clojure.walk :as walk])

  (walk/prewalk-demo)

  )