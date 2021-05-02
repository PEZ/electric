(ns hfdl.impl.trace
  (:require [hfdl.lang :refer [dataflow remote]]
            [hfdl.impl.compiler :as c]
            [hfdl.impl.util :as u]
            [missionary.core :as m]
            [minitest :refer [tests]])
  (:import (java.util.concurrent.atomic AtomicReference AtomicInteger)
           (clojure.lang IDeref IFn Box)
           (java.util.function IntBinaryOperator)))

(def events (u/monoid u/map-into [{} #{} #{}]))
(def change (partial assoc (events) 0))
(def create (partial assoc (events) 1))
(def sample (partial assoc (events) 2))

(def switch
  (let [iterator (int 0)
        notifier (int 1)
        terminator (int 2)
        transfer (int (bit-shift-left 1 0))
        operational (int (bit-shift-left 1 1))
        toggle (reify IntBinaryOperator (applyAsInt [_ x y] (bit-xor x y)))]
    (letfn [(next! [^AtomicReference sampler ^AtomicInteger control outer it in]
              (when (zero? (bit-and operational (.accumulateAndGet control operational toggle)))
                (if (nil? (u/aget-aset in iterator (u/aget-aset outer iterator it)))
                  (loop []
                    (if-some [x (.get sampler)]
                      (if (.compareAndSet sampler x in)
                        (if (ifn? x)
                          (do ((aget outer notifier)) (x))
                          (let [it (aget x iterator)]
                            (it) (try @it (catch Throwable _))))
                        (recur))
                      (do (.set sampler in) ((aget outer notifier))
                          (more! sampler control outer))))
                  (more! sampler control outer))))
            (more! [^AtomicReference sampler ^AtomicInteger control out]
              (when (zero? (.accumulateAndGet control transfer toggle))
                (if-some [t (aget out terminator)]
                  (loop []
                    (if-some [x (.get sampler)]
                      (do (aset out iterator (if (ifn? x) x (aget x iterator)))
                          (if (.compareAndSet sampler x nil) (do) (recur)))
                      (t)))
                  (let [it (aget out iterator)
                        in (object-array 1)]
                    (aset out iterator
                      (@it
                        #(if-some [it (aget in iterator)]
                           (if (.compareAndSet sampler it in)
                             ((aget out notifier))
                             (if (identical? it (aget out iterator))
                               ((aget out notifier))
                               (try @it (catch Throwable _))))
                           (next! sampler control out it in))
                        #(if-some [it (aget in iterator)]
                           (if (.compareAndSet sampler it nil)
                             (do) (if (identical? it (aget out iterator))
                                    ((aget out terminator))
                                    (more! sampler control out)))
                           (next! sampler control out it (aset in iterator in)))))
                    (next! sampler control out it in)))))]
      (fn [f]
        (fn [n t]
          (let [sampler (AtomicReference.)
                control (AtomicInteger.)
                out (doto (object-array 3) (aset notifier n))
                rdy #(more! sampler control out)]
            (aset out iterator (f rdy #(do (aset out terminator t) (rdy))))
            (rdy)
            (reify
              IFn
              (invoke [_]
                ;; TODO cancellation
                )
              IDeref
              (deref [_]
                ;; TODO catch exceptions
                ;; transfer should happen before cas to prevent post-failure notify
                @(loop []
                   (if-some [in (.get sampler)]
                     (let [it (aget in iterator)]
                      (if (.compareAndSet sampler in it)
                        it (recur)))
                     (aget out iterator)))))))))))

(comment
  (def in1 (atom 0))
  (def in2 (atom :a))
  (def out (atom (m/observe #(def ! %))))
  (def f (join (m/watch out)))
  (def it (f #(prn :ready) #(prn :done)))

  (reset! out (m/watch in2))
  (reset! in1 1)
  (reset! in2 :b)

  (! :ok)

  @it

  )

(defn sampler! [cb flow]
  ; flow is a continuous flow
  ; repl operator with two features
  ;   as lazy as the original flow, or maybe the lazyness is controlled
  ;     you control when the flow is actually sampled
  ;   memoized, so don't need to remember if flow is ready
  (let [memo (Box. nil)
        iter (Box. nil)
        sampler (reify IDeref
                  (deref [this]
                    ; user controlled sampling
                    ; check if value is dirty and if so, resample
                    (locking this
                      (let [x (.-val memo)]
                        (if (identical? x memo)
                          (loop []
                            (set! (.-val memo) nil)
                            (let [x @(.-val iter)]          ; sample
                              (if (identical? (.-val memo) memo)
                                (recur) (set! (.-val memo) x))))
                          x)))))
        ready! #(locking sampler
                  (if (identical? (.-val memo) memo)
                    (cb sampler) (set! (.-val memo) memo)))]
    (set! (.-val iter) (flow ready! #()))
    (ready!)))

(tests
  (def !a (atom 0))
  (sampler! #(def s %) (m/watch !a))
  @s := 0
  (swap! !a inc)
  (swap! !a inc)
  @s := 2
  )

(def peer
  (let [nodes (int 0)                                       ;; {node signal}
        inputs (int 1)                                      ;; {node cb}
        create-cb (int 2)
        sample-cb (int 3)
        local u/pure
        input (fn [s] (->> s (m/observe) (m/relieve {})))
        global (comp u/pure deref resolve)
        variable (fn [c t] (c/bind-context c (switch t)))]
    (fn [boot trace >read]
      (m/reactor
        (let [process (object-array 4)]
          (letfn [(context [{:keys [result graphs]} n t]
                    (reduce graph! nil (first graphs))
                    ((aget process create-cb) (second graphs))
                    (((aget process nodes) result) n t))
                  (listener [slot]
                    (->> (fn [!] (aset process slot !) u/nop)
                      (m/observe)
                      (m/relieve into)))
                  (spawn! [node ctor arg deps]
                    (reduce graph! nil deps)
                    (let [store (aget process nodes)]
                      (->> deps
                        (map store)
                        (apply ctor arg)
                        (m/signal!)
                        (assoc store node)
                        (aset process nodes))))
                  (graph! [_ node]
                    (when (nil? (get (aget process nodes) node))
                      (let [[op & args] node]
                        (case op
                          :remote (let [id (first args)]
                                    (spawn! node input
                                      (fn [!]
                                        (aset process inputs
                                          (assoc (aget process inputs)
                                            id !))) ())
                                    ((aget process sample-cb) #{id}))
                          :local (spawn! node local (first args) ())
                          :global (spawn! node global (first args) ())
                          :constant (spawn! node u/call u/pure args)
                          :variable (spawn! node variable context args)
                          :apply (spawn! node m/latest u/call (apply cons args))))))
                  (touch! [_ n x] ((get (aget process inputs) n) x))]
            (->> (m/ap
                   (u/amb=
                     (create (m/?? (listener create-cb)))
                     (sample (m/?? (listener sample-cb)))
                     (do (c/with-ctx context (boot))
                         (let [[changed created sampled] (m/?? (m/stream! >read))]
                           (reduce graph! nil sampled)
                           (reduce graph! nil created)
                           (reduce-kv touch! nil changed)
                           (let [node (m/?= (m/enumerate sampled))]
                             (change {node (m/?? (get (aget process nodes) node))}))))))
              (m/relieve u/map-into)
              (m/stream!)
              (u/foreach trace)
              (m/stream!))))))))

(defmacro df [& body]
  `(:result (dataflow ~@body)))

(defmacro debug [sym & body]
  `(fn []
     (sampler! (fn [s#] (println :ready ~sym) (def ~sym s#))
       ~@body)))



(comment
  ; Boot a peer (process reactor) with a boot function
  ; the boot function is for a dag
  ; the debug adds a way to sample the result on the side for testing
  ; (in production the result is never sampled, process will fire more effects)

  ((peer (debug sampler (* 6 7))                            ; dag boot fn
     #(m/sp (prn %))                                        ; trace spout
     (u/poll m/never))                                      ; no network inputs
   prn prn)

  @sampler

  )

(defn shell [s f]
  (let [in (m/mbx)
        out (m/rdv)
        cancel ((peer u/nop out (u/poll in)) s f)]
    (fn
      ([] (cancel))
      ([e] (in e))
      ([s f] (out s f)))))

(comment

  (def $ (shell prn prn))
  ($ (sample #{(df (* 6 7))}))
  (m/? $) := (change {(df (* 6 7)) 42})

  ($ (sample #{(df (* 6 (remote 7)))}))
  (m/? $) := (sample #{(df 7)})

  ($ (change {(df 7) 7}))
  (m/? $) := (change {(df (* 6 (remote 7))) 42})

  ($ (change {(df 7) 8}))
  (m/? $) := (change {(df (* 6 (remote 7))) 48})
  )

(defn system [boot]
  ; test emulator of distributed system
  (m/sp
    (let [l->r (m/rdv)
          r->l (m/rdv)]
      (m/? (m/join vector
             (peer #() #(do (println 'r->l %) (r->l %)) (u/poll l->r))
             (peer boot #(do (println 'l->r %) (l->r %)) (u/poll r->l)))))))

(comment

  (def !a1 (atom 6))
  (def !a2 (atom 7))

  (def dag (dataflow (* @(m/watch !a1) @(m/watch !a2))))

  ; does nothing, the boot had no effect and the return value is ignored
  ;(def system-task (system (fn [] dag)))

  ; prod server - run for effects, don't ever sample, that's fine
  (def system-task (system (fn [] (dag #() #()))))

  ; repl server – run for effects but with hook for user to read the result at some time
  (def system-task (system (fn [] (sampler! #(def sampler %) dag))))
  (system-task prn prn)                                     ; runs forever
  @sampler := 42
  @sampler := 42

  (swap! !a1 inc)
  @sampler := 49

  )

(comment
  (def !input (atom "alice"))
  (defn form-input [] (m/watch !input))
  (defn render-table [>x] (m/relieve {}
                            (dataflow
                              (prn :render-table @>x))
                           #_(m/ap (prn :render-table (m/?! x)))))
  (defn query [q] [q])

  ; what touches the network is eager
  ; if something is required remotely we don't lazy sample,
  ; we just pass the value eagerly and then when we receive it
  ; we can turn that into a lazy sampling.

  ; why? its debatable
  ; Leo says: think this is the behavior that will minimize
  ; experienced latency

  ; there are two types of effects with network between
  ; user interaction effects -> network -> rendering effects

  (def system-task
    (system (debug sampler
              (dataflow
                (let [needle @(form-input)
                      results (remote (query needle))]
                  @(render-table ~results))))))
  (system-task prn prn)
  ; no prints
  @sampler := nil
  ; :render-table ["alice"]
  (reset! !input "bob")
  ; nothing yet
  @sampler := nil
  ; :render-table ["bob"]
  )