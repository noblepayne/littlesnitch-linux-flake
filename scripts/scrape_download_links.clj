(require '[babashka.fs :as fs])
(load-file (str (fs/path (fs/parent *file*) "hickory_bundle.clj")))
(ns scrape-download-links
  {:clj-kondo/config '{:linters {:unresolved-symbol {:exclude [(babashka.fs/with-temp-dir)]}}}}
  (:require
   [babashka.http-client :as http]
   [babashka.fs :as fs]
   [babashka.process :as proc]
   [clojure.string :as str]
   [hickory-bundle :as h]
   [hickory.select :as hs]))

;; --- Utilities ---

(defn sha256-base64 [input]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bytes (if (string? input) (.getBytes input "UTF-8") input)
        digest (.digest md bytes)]
    (.encodeToString (java.util.Base64/getEncoder) digest)))

(defn sha256-sri [input]
  (str "sha256-" (sha256-base64 input)))

(defn sh-check! [cmd args]
  (let [res (apply proc/sh cmd args)]
    (if (zero? (:exit res))
      res
      (throw (ex-info (str "Command failed: " cmd " " (str/join " " args)) res)))))

;; --- Domain Logic ---

(def arch-map
  {"x86_64"  "x86_64-linux"
   "aarch64" "aarch64-linux"
   "ppc64le" "ppc64le-linux"
   "riscv64" "riscv64-linux"})

(defn extract-version [url]
  (some-> (re-find #"littlesnitch-(\d+\.\d+\.\d+(?:-\d+)?)-" url) second))

(defn parse-hrefs [html]
  (->> (h/as-hickory (h/parse html))
       (hs/select (hs/class "download-table"))
       first
       (hs/select (hs/tag :a))
       (map #(get-in % [:attrs :href]))
       (filter #(str/ends-with? % ".pkg.tar.zst"))
       (map #(str "https://obdev.at" %))
       distinct
       sort))

(defn gen-sources-nix [version arch-data]
  (let [indent "    "
        lines (for [[system {:keys [url hash]}] (sort-by key arch-data)]
                (str "  " system " = {\n"
                     indent "url = \"" url "\";\n"
                     indent "hash = \"" hash "\";\n"
                     "  };"))]
    (str "{\n"
         "  version = \"" version "\";\n"
         (str/join "\n" lines)
         "\n}\n")))

;; --- Actions ---

(defn fetch-arch-data [urls]
  (into {} (for [url urls
                 :let [arch (some #(when (str/includes? url %) %) (keys arch-map))
                       system (get arch-map arch)]
                 :when system]
             (do
               (println "Fetching and hashing" arch "...")
               [system {:url url
                        :hash (sha256-sri (:body (http/get url {:as :bytes})))}]))))

(defn perform-update! [manifest-hash urls]
  (let [version (extract-version (first urls))
        _ (println "Detected version:" version)
        arch-data (fetch-arch-data urls)
        nix-content (gen-sources-nix version arch-data)]

    (println "Writing pkgs/sources.nix...")
    (spit "pkgs/sources.nix" nix-content)

    (println "Updating stored manifest hash...")
    (spit ".littlesnitch-url-hash" manifest-hash)

    (println "Running build test...")
    (sh-check! "nix" ["build" ".#littlesnitch" "--extra-experimental-features" "flakes" "--impure"])

    (println "Writing version to .version file...")
    (spit ".version" version)

    (println "Running smoke test...")
    (let [res (sh-check! "./result/bin/littlesnitch" ["--version"])]
      (println "Smoke test passed:")
      (print (:out res)))))

;; --- Entry Point ---

(defn -main [& _]
  (try
    (println "Fetching download page...")
    (let [html (-> "https://obdev.at/products/littlesnitch-linux/download.html" http/get :body)
          urls (parse-hrefs html)
          manifest-hash (sha256-sri (str/join "\n" urls))
          stored-hash (when (fs/exists? ".littlesnitch-url-hash")
                        (str/trim (slurp ".littlesnitch-url-hash")))]

      (if (= manifest-hash stored-hash)
        (println "No changes detected in download manifest. Exiting.")
        (do
          (println "Manifest changed! Starting update...")
          (perform-update! manifest-hash urls)
          (println "\nUpdate complete!")))
      (System/exit 0))
    (catch Exception e
      (binding [*out* *err*]
        (println "Error during update:" (.getMessage e))
        (when-let [res (ex-data e)]
          (println "Process output:")
          (print (:err res))))
      (System/exit 1))))

(-main)
