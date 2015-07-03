(ns leiningen.cljs-lambda
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [leiningen.cljs-lambda.zip-tedium :refer [write-zip]]
            [leiningen.cljs-lambda.aws :as aws]
            [leiningen.npm :as npm]
            [leiningen.cljsbuild :as cljsbuild]
            [leiningen.cljsbuild.config :as cljsbuild.config]
            [stencil.core :as stencil])
  (:import [java.io File]))

(defn- qualified->under [& args]
  (str/join "_" (mapcat #(str/split (str %) #"\.|-|/") args)))

(defn- generate-index [{:keys [output-to output-dir]} fns]
  (let [template (slurp (io/resource "index.mustache"))]
    (stencil/render-string
     template
     {:output-to output-to
      :output-dir output-dir
      :module
      (for [[ns fns] (group-by namespace (map :invoke fns))]
        {:name ns
         :function (for [f (map name fns)]
                     {:underscores (qualified->under ns f)
                      :dots (str ns "." f)})})})))

(defn- write-index [output-dir s]
  (let [file (io/file output-dir "index.js")]
    (println "Writing index to" (.getAbsolutePath file))
    (with-open [w (io/writer file)]
      (.write w s))
    (.getPath file)))

(defn- augment-project
  [{{:keys [defaults functions cljs-build-id]} :cljs-lambda
    :as project}]
  (let [{:keys [builds]} (cljsbuild.config/extract-options project)
        [build] (if-not cljs-build-id
                  builds
                  (filter #(= (:id %) cljs-build-id) builds))]
    (if-not build
      (leiningen.core.main/abort "Can't find cljsbuild build")
      (-> project
          (assoc-in [:cljs-lambda :cljs-build] build)
          (assoc-in [:cljs-lambda :functions]
                    (map (fn [m]
                           (assoc (merge defaults m)
                                  :handler (qualified->under (:invoke m))))
                         functions))))))

(defn build
  "Write a zip file suitable for Lambda deployment"
  [{{:keys [cljs-build cljs-build-id functions]} :cljs-lambda
    :as project}]

  (npm/npm project "install")
  (cljsbuild/cljsbuild project "once" (:id cljs-build))
  (let [{{:keys [output-dir output-to]} :compiler} cljs-build
        project-name (-> project :name name)
        index-path   (->> functions
                          (generate-index
                           {:output-dir output-dir
                            :output-to output-to})
                          (write-index output-dir))]
    (write-zip
     {:project-name project-name
      :index-path index-path
      :out-path output-dir
      :zip-name (str project-name ".zip")})))

(defn deploy
  "Build & deploy a zip file to Lambda, exposing the specified functions"
  [{:keys [aws cljsbuild cljs-lambda] :as project}]
  (let [zip-path (build project)
        {{:keys [output-dir output-to]} :compiler} cljsbuild
        region (-> aws :lambda :region (or :us-east-1))]
    (println "Deploying to region" region)
    (aws/deploy
     zip-path
     region aws cljs-lambda)))

(defn cljs-lambda
  "Build & deploy AWS Lambda functions"
  {:help-arglists '([build deploy]) :subtasks [#'build #'deploy]}

  ([project] (println (leiningen.help/help-for cljs-lambda)))

  ([project subtask & args]
   (if-let [subtask-fn ({"build" build "deploy" deploy} subtask)]
     (apply subtask-fn (augment-project project) args)
     (println (leiningen.help/help-for cljs-lambda)))))