(require '[lumo.build.api :as b])

(b/build "src"
  {:main 'garage-mqtt.core
   :output-to "main.js"
   :optimizations :none
   :install-deps true
   :npm-deps {:mqtt "2.18.8"}
   :target :nodejs})

