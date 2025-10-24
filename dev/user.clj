(ns user)

(comment
  (do (require '[nextjournal.clerk :as clerk])
      (clerk/serve! {:browse? true})))
