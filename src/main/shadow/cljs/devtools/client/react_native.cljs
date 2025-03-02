(ns shadow.cljs.devtools.client.react-native
  (:require
    [clojure.string :as str]
    [cljs.reader :as reader]
    [goog.object :as gobj]
    [goog.net.XhrIo :as xhr]
    [shadow.cljs.devtools.client.env :as env]
    ))

(defonce repl-ns-ref (atom nil))

(defonce socket-ref (volatile! nil))

(defn ws-msg [msg]
  (if-let [s @socket-ref]
    (.send s (pr-str msg))
    (js/console.warn "WEBSOCKET NOT CONNECTED" (pr-str msg))))

(def loaded? js/goog.isProvided_)

(defn goog-is-loaded? [name]
  (js/$CLJS.SHADOW_ENV.isLoaded name))

(def goog-base-rc
  [:shadow.build.classpath/resource "goog/base.js"])

(defn devtools-msg
  ([x]
   (js/console.log x))
  ([x y]
   (js/console.log x y)))

(defn src-is-loaded? [{:keys [resource-id output-name] :as src}]
  ;; FIXME: don't like this special case handling, but goog/base.js will always be loaded
  ;; but not as a separate file
  (or (= goog-base-rc resource-id)
      (goog-is-loaded? output-name)))

(defn script-eval [code]
  (js/goog.global.eval code))

(defn do-js-load [sources]
  (doseq [{:keys [resource-name js] :as src} sources]
    (devtools-msg "load JS" resource-name)
    (env/before-load-src src)
    (script-eval (str js "\n//# sourceURL=" resource-name))))

(defn do-js-reload [msg sources complete-fn]
  (env/do-js-reload
    (assoc msg
      :log-missing-fn
      (fn [fn-sym]
        (devtools-msg (str "can't find fn " fn-sym)))
      :log-call-async
      (fn [fn-sym]
        (devtools-msg (str "call async " fn-sym)))
      :log-call
      (fn [fn-sym]
        (devtools-msg (str "call " fn-sym))))
    #(do-js-load sources)
    complete-fn))

(defn load-sources [sources callback]
  (if (empty? sources)
    (callback [])
    (xhr/send
      (env/files-url)
      (fn [res]
        (this-as ^goog req
          (let [content
                (-> req
                    (.getResponseText)
                    (reader/read-string))]
            (callback content)
            )))
      "POST"
      (pr-str {:client :browser
               :sources (into [] (map :resource-id) sources)})
      #js {"content-type" "application/edn; charset=utf-8"})))

(defn noop [& args])

(defn handle-build-complete [{:keys [info reload-info] :as msg}]
  (let [{:keys [sources compiled]}
        info

        warnings
        (->> (for [{:keys [resource-name warnings] :as src} sources
                   :when (not (:from-jar src))
                   warning warnings]
               (assoc warning :resource-name resource-name))
             (distinct)
             (into []))]

    (when (and env/autoload
               (or (empty? warnings) env/ignore-warnings))

      (let [sources-to-get
            (->> sources
                 ;; don't reload namespaces that have ^:dev/never-reload meta
                 (remove (fn [{:keys [ns]}]
                           (contains? (:never-load reload-info) ns)))
                 (filter
                   (fn [{:keys [ns resource-id] :as src}]
                     (or (contains? (:always-load reload-info) ns)
                         (not (src-is-loaded? src))
                         (and (contains? compiled resource-id)
                              ;; never reload files from jar
                              ;; they can't be hot-swapped so the only way they get re-compiled
                              ;; is if they have warnings, which we can't to anything about
                              (not (:from-jar src))))))
                 (into []))]

        (when (seq sources-to-get)
          (load-sources sources-to-get #(do-js-reload msg % noop))
          )))))

(defn repl-error [e]
  (js/console.error "repl/invoke error" (.-message e) e)
  (env/repl-error e))

(defn repl-invoke [{:keys [id js]}]
  (let [result (env/repl-call #(js/eval js) repl-error)]
    (-> result
        (assoc :id id)
        (ws-msg))))

(defn repl-require [{:keys [id sources reload-namespaces js-requires] :as msg} done]
  (let [sources-to-load
        (->> sources
             (remove (fn [{:keys [provides] :as src}]
                       (and (src-is-loaded? src)
                            (not (some reload-namespaces provides)))))
             (into []))]

    (load-sources
      sources-to-load
      (fn [sources]
        (do-js-load sources)
        (ws-msg {:type :repl/require-complete :id id})
        (done)
        ))))

(defn repl-init [{:keys [repl-state id]} done]
  (reset! repl-ns-ref (get-in repl-state [:current :ns]))
  (load-sources
    ;; maybe need to load some missing files to init REPL
    (->> (:repl-sources repl-state)
         (remove src-is-loaded?)
         (into []))
    (fn [sources]
      (do-js-load sources)
      (ws-msg {:type :repl/init-complete :id id})
      (devtools-msg "REPL init successful")
      (done))))

(defn repl-set-ns [{:keys [id ns]}]
  (reset! repl-ns-ref ns)
  (ws-msg {:type :repl/set-ns-complete :id id :ns ns}))

;; FIXME: core.async-ify this
(defn handle-message [{:keys [type] :as msg} done]
  ;; (js/console.log "ws-msg" (pr-str msg))
  (case type
    :repl/invoke
    (repl-invoke msg)

    :repl/require
    (repl-require msg done)

    :repl/set-ns
    (repl-set-ns msg)

    :repl/init
    (repl-init msg done)

    :build-complete
    (handle-build-complete msg)

    :build-failure
    nil

    :build-init
    nil

    :build-start
    nil

    :pong
    nil

    :client/stale
    (devtools-msg "Stale Client! You are not using the latest compilation output!")

    :client/no-worker
    (devtools-msg (str "watch for build \"" env/build-id "\" not running"))

    ;; default
    :ignored)

  (when-not (contains? env/async-ops type)
    (done)))

(defn heartbeat! []
  (when-let [s @socket-ref]
    (.send s (pr-str {:type :ping :v (js/Date.now)}))
    (js/setTimeout heartbeat! 30000)))

(defn ws-connect []
  (let [ws-url
        (env/ws-url :react-native)

        socket
        (js/WebSocket. ws-url)]


    (vreset! socket-ref socket)

    (set! (.-onmessage socket)
      (fn [e]
        (env/process-ws-msg (. e -data) handle-message)
        ))

    (set! (.-onopen socket)
      (fn [e]
        ;; :module-format :js already patches provide
        (when (= "goog" env/module-format)
          ;; patch away the already declared exception
          (set! (.-provide js/goog) js/goog.constructNamespace_))

        (env/set-print-fns! ws-msg)

        (devtools-msg "WebSocket connected!")
        ))

    (set! (.-onclose socket)
      (fn [e]
        ;; not a big fan of reconnecting automatically since a disconnect
        ;; may signal a change of config, safer to just reload the page
        (devtools-msg "WebSocket disconnected!")
        (vreset! socket-ref nil)
        (env/reset-print-fns!)
        ))

    (set! (.-onerror socket)
      (fn [e]
        (js/console.error "websocket error" (.-message e) e)))

    (js/setTimeout heartbeat! 30000)
    ))

(when ^boolean env/enabled
  ;; disconnect an already connected socket, happens if this file is reloaded
  ;; pretty much only for me while working on this file
  (when-let [s @socket-ref]
    (devtools-msg "connection reset!")
    (set! (.-onclose s) (fn [e]))
    (.close s)
    (vreset! socket-ref nil))

  (ws-connect))
