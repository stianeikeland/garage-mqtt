(ns garage-mqtt.helpers)

(defn log [& what]
  (let [ts  (.toISOString (new js/Date (js/Date.now)))
        msg (apply str what)]
    (println (str "[" ts "] " msg))))

(defn env-or-default [var default]
  (or (aget js/process.env var)
      default))
