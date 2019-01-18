(require '[lumo.build.api :as b])

(b/build "src"
  {:main 'garage-mqtt.core
   :output-to "main.js"
   :optimizations :none
   :target :nodejs})

