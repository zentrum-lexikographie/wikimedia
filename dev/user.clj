(ns user)

(comment
  (require '[nextjournal.clerk :as clerk])
  (clerk/serve! {:browse? true}))
