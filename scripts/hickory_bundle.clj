(ns hickory.zip
  (:require [clojure.zip :as zip]))

;;
;; Hickory
;;

(defn hickory-zip
  "Returns a zipper for html dom maps (as from as-hickory),
  given a root element."
  [root]
  (zip/zipper (complement string?)
              (comp seq :content)
              (fn [node children]
                (assoc node :content (and children (apply vector children))))
              root))

;;
;; Hiccup
;;

;; Just to make things easier, we go ahead and do the work here to
;; make hiccup zippers work on both normalized (all items have tag,
;; attrs map, and any children) and unnormalized hiccup forms.

(defn- children
  "Takes a hiccup node (normalized or not) and returns its children nodes."
  [node]
  (if (vector? node)
    ;; It's a hiccup node vector.
    (if (map? (second node)) ;; There is an attr map in second slot.
      (seq (subvec node 2))  ;; So skip tag and attr vec.
      (seq (subvec node 1))) ;; Otherwise, just skip tag.
    ;; Otherwise, must have a been a node list
    node))

;; Note, it's not made clear at all in the docs for clojure.zip, but as far as
;; I can tell, you are given a node potentially with existing children and
;; the sequence of children that should totally replace the existing children.
(defn- make
  "Takes a hiccup node (normalized or not) and a sequence of children nodes,
   and returns a new node that has the the children argument as its children."
  [node children]
  ;; The node might be either a vector (hiccup form) or a seq (which is like a
  ;; node-list).
  (if (vector? node)
    (if (map? (second node))                 ;; Again, check for normalized vec.
      (into (subvec node 0 2) children)      ;; Attach children after tag&attrs.
      (apply vector (first node) children))  ;; Otherwise, attach after tag.
    children))   ;; We were given a list for node, so just return the new list.

(defn hiccup-zip
  "Returns a zipper for Hiccup forms, given a root form."
  [root]
  (zip/zipper sequential?
              children
              make
              root))
(ns hickory.utils
  "Miscellaneous utilities used internally."
  (:require [clojure.string :as string]
            #?(:cljs [goog.string :as gstring])))

;;
;; Data
;;

(def void-element
  "Elements that don't have a meaningful <tag></tag> form."
  #{:area :base :br :col :command :embed :hr :img :input :keygen :link :meta
    :param :source :track :wbr})

(def unescapable-content
  "Elements whose content should never have html-escape codes."
  #{:script :style})

;;
;; String utils
;;

(defn clj-html-escape-without-quoin
  "Actually copy pasted from quoin: https://github.com/davidsantiago/quoin/blob/develop/src/quoin/text.clj"
  [^String s]
  ;; This method is "Java in Clojure" for serious speedups.
  #?(:clj (let [sb (StringBuilder.)
                slength (long (count s))]
            (loop [idx (long 0)]
              (if (>= idx slength)
                (.toString sb)
                (let [c (char (.charAt s idx))]
                  (case c
                    \& (.append sb "&amp;")
                    \< (.append sb "&lt;")
                    \> (.append sb "&gt;")
                    \" (.append sb "&quot;")
                    (.append sb c))
                  (recur (inc idx))))))
     ;; This shouldn't be called directly in cljs, but if it is, we use the same implementation as the html-escape function
     :cljs (gstring/htmlEscape s)))

(defn html-escape
  [s]
  #?(:clj  (clj-html-escape-without-quoin s)
     :cljs (gstring/htmlEscape s)))

(defn starts-with
  [^String s ^String prefix]
  #?(:clj  (.startsWith s prefix)
     :cljs (goog.string.startsWith s prefix)))

(defn lower-case-keyword
  "Converts its string argument into a lowercase keyword."
  [s]
  (-> s string/lower-case keyword))

(defn render-doctype
  "Returns a string containing the HTML source for the doctype with given args.
   The second and third arguments can be nil or empty strings."
  [name publicid systemid]
  (str "<!DOCTYPE " name
       (when (not-empty publicid)
         (str " PUBLIC \"" publicid "\""))
       (when (not-empty systemid)
         (str " \"" systemid "\""))
       ">"))
(ns hickory.core
  (:require [hickory.utils :as utils]
            [hickory.zip :as hzip]
            [clojure.zip :as zip])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Attribute Attributes Comment DataNode Document
            DocumentType Element TextNode XmlDeclaration]
           [org.jsoup.parser Tag Parser]))

(set! *warn-on-reflection* true)

(defn- end-or-recur [as-fn loc data & [skip-child?]]
  (let [new-loc (-> loc (zip/replace data) zip/next (cond-> skip-child? zip/next))]
    (if (zip/end? new-loc)
      (zip/root new-loc)
      #(as-fn (zip/node new-loc) new-loc))))

;;
;; Protocols
;;

(defprotocol HiccupRepresentable
  "Objects that can be represented as Hiccup nodes implement this protocol in
   order to make the conversion."
  (as-hiccup [this] [this zip-loc]
    "Converts the node given into a hiccup-format data structure. The
     node must have an implementation of the HiccupRepresentable
     protocol; nodes created by parse or parse-fragment already do."))

(defprotocol HickoryRepresentable
  "Objects that can be represented as HTML DOM node maps, similar to
   clojure.xml, implement this protocol to make the conversion.

   Each DOM node will be a map or string (for Text/CDATASections). Nodes that
   are maps have the appropriate subset of the keys

     :type     - [:comment, :document, :document-type, :element]
     :tag      - node's tag, check :type to see if applicable
     :attrs    - node's attributes as a map, check :type to see if applicable
     :content  - node's child nodes, in a vector, check :type to see if
                 applicable"
  (as-hickory [this] [this zip-loc]
    "Converts the node given into a hickory-format data structure. The
     node must have an implementation of the HickoryRepresentable protocol;
     nodes created by parse or parse-fragment already do."))

(extend-protocol HiccupRepresentable
  Attribute
  ;; Note the attribute value is not html-escaped; see comment for Element.
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this _] [(utils/lower-case-keyword (.getKey this)) (.getValue this)]))
  Attributes
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this _] (into {} (map as-hiccup this))))
  Comment
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc] (end-or-recur as-hiccup loc (str "<!--" (.getData this) "-->"))))
  DataNode
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc] (end-or-recur as-hiccup loc (str this))))
  Document
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc] (end-or-recur as-hiccup loc (apply list (.childNodes this)))))
  DocumentType
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc]
     (end-or-recur as-hiccup loc (utils/render-doctype (.name this)
                                                       (.publicId this)
                                                       (.systemId this)))))
  Element
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc]
     ;; There is an issue with the hiccup format, which is that it
     ;; can't quite cover all the pieces of HTML, so anything it
     ;; doesn't cover is thrown into a string containing the raw
     ;; HTML. This presents a problem because it is then never the case
     ;; that a string in a hiccup form should be html-escaped (except
     ;; in an attribute value) when rendering; it should already have
     ;; any escaping. Since the HTML parser quite properly un-escapes
     ;; HTML where it should, we have to go back and un-un-escape it
     ;; wherever text would have been un-escaped. We do this by
     ;; html-escaping the parsed contents of text nodes, and not
     ;; html-escaping comments, data-nodes, and the contents of
     ;; unescapable nodes.
     (let [tag (utils/lower-case-keyword (.tagName this))
           children (cond->> (.childNodes this) (utils/unescapable-content tag) (map str))
           data (into [] (concat [tag (trampoline as-hiccup (.attributes this))] children))]
       (end-or-recur as-hiccup loc data (utils/unescapable-content tag)))))
  TextNode
  ;; See comment for Element re: html escaping.
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc] (end-or-recur as-hiccup loc (utils/html-escape (.getWholeText this)))))
  XmlDeclaration
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc] (end-or-recur as-hiccup loc (str this)))))

(extend-protocol HickoryRepresentable
  Attribute
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this _] [(utils/lower-case-keyword (.getKey this)) (.getValue this)]))
  Attributes
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this _] (not-empty (into {} (map as-hickory this)))))
  Comment
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc {:type :comment
                                              :content [(.getData this)]} true)))
  DataNode
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc (str this))))
  Document
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc {:type :document
                                              :content (or (seq (.childNodes this)) nil)})))
  DocumentType
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc {:type :document-type
                                              :attrs (trampoline as-hickory (.attributes this))})))
  Element
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc {:type :element
                                              :attrs (trampoline as-hickory (.attributes this))
                                              :tag (utils/lower-case-keyword (.tagName this))
                                              :content (or (seq (.childNodes this)) nil)})))
  TextNode
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc (.getWholeText this)))))

;; Jsoup/parse is polymorphic, we'll let reflection handle it for now
(set! *warn-on-reflection* false)

(defn parse
  "Parse an entire HTML document into a DOM structure that can be
   used as input to as-hiccup or as-hickory."
  [s]
  (Jsoup/parse s))

(set! *warn-on-reflection* true)

(defn parse-fragment
  "Parse an HTML fragment (some group of tags that might be at home somewhere
   in the tag hierarchy under <body>) into a list of DOM elements that can
   each be passed as input to as-hiccup or as-hickory."
  [s]
  (into [] (Parser/parseFragment s (Element. (Tag/valueOf "body") "") "")))
(ns hickory.select
  "Functions to query hickory-format HTML data.

   See clojure.zip for more information on zippers, locs, nodes, next, etc."
  (:require [clojure.zip :as zip]
            [clojure.string :as string]
            [hickory.zip :as hzip])
  #?(:clj
     (:import clojure.lang.IFn))
  (:refer-clojure :exclude [and or not class]))

;;
;; Utilities
;;

(defn until
  "Calls f on val until pred called on the result is true. If not, it
   repeats by calling f on the result, etc. The value that made pred
   return true is returned."
  [f val pred]
  (let [next-val (f val)]
    (if (pred next-val)
      next-val
      (recur f next-val pred))))

(defn count-until
  "Calls f on val until pred called on the result is true. If not, it
   repeats by calling f on the result, etc. The count of times this
   process was repeated until pred returned true is returned."
  [f val pred]
  (loop [next-val val
         cnt 0]
    (if (pred next-val)
      cnt
      (recur (f next-val) (inc cnt)))))

(defn next-pred
  "Like clojure.zip/next, but moves until it reaches a node that returns
   true when the function in the pred argument is called on them, or reaches
   the end."
  [hzip-loc pred]
  (until zip/next hzip-loc #(clojure.core/or (zip/end? %)
                                             (pred %))))

(defn prev-pred
  "Like clojure.zip/prev, but moves until it reaches a node that returns
   true when the function in the pred argument is called on them, or reaches
   the beginning."
  [hzip-loc pred]
  (until zip/prev hzip-loc #(clojure.core/or (nil? %)
                                             (pred %))))

(defn left-pred
  "Like clojure.zip/left, but moves until it reaches a node that returns
   true when the function in the pred argument is called on them, or reaches
   the left boundary of the current group of siblings."
  [hzip-loc pred]
  (until zip/left hzip-loc #(clojure.core/or (nil? %)
                                             (pred %))))

(defn right-pred
  "Like clojure.zip/right, but moves until it reaches a node that returns
   true when the function in the pred argument is called on them, or reaches
   the right boundary of the current group of siblings."
  [hzip-loc pred]
  (until zip/right hzip-loc #(clojure.core/or (nil? %)
                                              (pred %))))

(defn up-pred
  "Like clojure.zip/up, but moves until it reaches a node that returns
   true when the function in the pred argument is called on them, or reaches
   the beginning."
  [hzip-loc pred]
  (until zip/up hzip-loc #(clojure.core/or (nil? %)
                                           (pred %))))

(defn next-of-node-type
  "Like clojure.zip/next, but only counts moves to nodes that have
   the given type."
  [hzip-loc node-type]
  (next-pred hzip-loc #(= node-type (:type (zip/node %)))))

(defn prev-of-node-type
  "Like clojure.zip/prev, but only counts moves to nodes that have
   the given type."
  [hzip-loc node-type]
  (prev-pred hzip-loc #(= node-type (:type (zip/node %)))))

(defn left-of-node-type
  "Like clojure.zip/left, but only counts moves to nodes that have
   the given type."
  [hzip-loc node-type]
  (left-pred hzip-loc #(= node-type (:type (zip/node %)))))

(defn right-of-node-type
  "Like clojure.zip/right, but only counts moves to nodes that have
   the given type."
  [hzip-loc node-type]
  (right-pred hzip-loc #(= node-type (:type (zip/node %)))))

(defn after-subtree
  "Given a zipper loc, returns the zipper loc that is the first one after
   the arg's subtree, if there is a subtree. If there is no loc after this
   loc's subtree, returns the end node."
  [zip-loc]
  (if (zip/end? zip-loc)
    zip-loc
    (clojure.core/or (zip/right zip-loc)
                     (loop [curr-loc zip-loc]
                       (if (zip/up curr-loc)
                         (clojure.core/or (zip/right (zip/up curr-loc))
                                          (recur (zip/up curr-loc)))
                         [(zip/node curr-loc) :end])))))

;;
;; Select
;;

(defn select-next-loc
  "Given a selector function and a loc inside a hickory zip data structure,
   returns the next zipper loc that satisfies the selection function. This can
   be the loc that is passed in, so be sure to move to the next loc if you
   want to use this function to exhaustively search through a tree manually.
   Note that if there is no next node that satisfies the selection function, nil
   is returned.

   The third argument, if present, must be a function of one argument that is
   called on a zipper loc to return the next loc to consider in the search. By
   default, this argument is zip/next. The fourth argument, if present, must be
   a function of one argument that is called on a zipper loc to determine if
   the end of the search has been reached (true return value). When the fourth
   argument returns true on a loc, that loc is not considered in the search and
   the search finishes with a nil return. By default, the fourth argument is
   zip/end?."
  ([selector-fn hzip-loc]
   (select-next-loc selector-fn hzip-loc zip/next))
  ([selector-fn hzip-loc next-fn]
   (select-next-loc selector-fn hzip-loc next-fn zip/end?))
  ([selector-fn hzip-loc next-fn end?-fn]
   (loop [loc hzip-loc]
     (if (end?-fn loc)
       nil
       (if (selector-fn loc)
         loc
         (recur (next-fn loc)))))))

(defn select-locs
  "Given a selector function and a hickory data structure, returns a vector
   containing all of the zipper locs selected by the selector function."
  [selector-fn hickory-tree]
  (loop [loc (select-next-loc selector-fn
                              (hzip/hickory-zip hickory-tree))
         selected-nodes (transient [])]
    (if (nil? loc)
      (persistent! selected-nodes)
      (recur (select-next-loc selector-fn (zip/next loc))
             (conj! selected-nodes loc)))))

(defn select
  "Given a selector function and a hickory data structure, returns a vector
   containing all of the hickory nodes selected by the selector function."
  [selector-fn hickory-tree]
  (mapv zip/node (select-locs selector-fn hickory-tree)))

;;
;; Selectors
;;
;; Mostly based off the spec at http://www.w3.org/TR/selectors/#selectors
;; Some selectors are simply not possible outside a browser (active,
;; visited, etc).
;;

(defn node-type
  "Return a function that takes a zip-loc argument and returns the
   zip-loc passed in iff it has the given node type. The type
   argument can be a String or Named (keyword, symbol). The node type
   comparison is done case-insensitively."
  [type]
  (fn [hzip-loc]
    (let [node (zip/node hzip-loc)
          node-type (-> node :type)]
      (if (clojure.core/and node-type
                            (= (string/lower-case (name node-type))
                               (string/lower-case (name type))))
        hzip-loc))))

(defn tag
  "Return a function that takes a zip-loc argument and returns the
   zip-loc passed in iff it has the given tag. The tag argument can be
   a String or Named (keyword, symbol). The tag name comparison
   is done case-insensitively."
  [tag]
  (fn [hzip-loc]
    (let [node (zip/node hzip-loc)
          node-tag (-> node :tag)]
      (if (clojure.core/and node-tag
                            (= (string/lower-case (name node-tag))
                               (string/lower-case (name tag))))
        hzip-loc))))

(defn attr
  "Returns a function that takes a zip-loc argument and returns the
   zip-loc passed in iff it has the given attribute, and that attribute
   optionally satisfies a predicate given as an additional argument. With
   a single argument, the attribute name (a string, keyword, or symbol),
   the function returned will return the zip-loc if that attribute is
   present (and has any value) on the zip-loc's node. The attribute name
   will be compared case-insensitively, but the attribute value (if present),
   will be passed as-is to the predicate.

   If the predicate argument is given, it will only return the zip-loc if
   that predicate is satisfied when given the attribute's value as its only
   argument. Note that the predicate only gets called when the attribute is
   present, so it can assume its argument is not nil."
  ([attr-name]
     ;; Since we want this call to succeed in any case where this attr
     ;; is present, we pass in a function that always returns true.
   (attr attr-name (fn [_] true)))
  ([attr-name predicate]
     ;; Note that attribute names are normalized to lowercase by
     ;; jsoup, as an html5 parser should; see here:
     ;; http://www.whatwg.org/specs/web-apps/current-work/#attribute-name-state
   (fn [hzip-loc]
     (let [node (zip/node hzip-loc)
           attr-key (keyword (string/lower-case (name attr-name)))]
         ;; If the attribute does not exist, we'll definitely return null.
         ;; Otherwise, we'll ask the predicate if we should return hzip-loc.
       (if (clojure.core/and (contains? (:attrs node) attr-key)
                             (predicate (get-in node [:attrs attr-key])))
         hzip-loc)))))

(defn id
  "Returns a function that takes a zip-loc argument and returns the
   zip-loc passed in iff it has the given id. The id argument can be
   a String or Named (keyword, symbol). The id name comparison
   is done case-insensitively."
  [id]
  (attr :id #(= (string/lower-case %)
                (string/lower-case (name id)))))

(defn class
  "Returns a function that takes a zip-loc argument and returns the
   zip-loc passed in iff it has the given class. The class argument can
   be a String or Named (keyword, symbol). The class name comparison
   is done case-insensitively."
  [class-name]
  (letfn [(parse-classes [class-str]
            (into #{} (mapv string/lower-case
                            (string/split class-str #"\s+"))))]
    (attr :class #(contains? (parse-classes %)
                             (string/lower-case (name class-name))))))

(defn any
  "This selector takes no args, it simply is the selector function. It returns
   true on any element it is called on; corresponds to the CSS '*' selector."
  [hzip-loc]
  (if (= :element (-> (zip/node hzip-loc) :type))
    hzip-loc))

(def element
  "Another name for the any selector, to express that it can be used to only
   select elements."
  any)

(defn element-child
  "This selector takes no args, it simply is the selector function. It returns
   the zip-loc passed in iff that loc is an element, and it has a parent
   that is also an element."
  [hzip-loc]
  (let [possible-parent (zip/up hzip-loc)]
    (clojure.core/and (element hzip-loc)
                      ;; Check that we are not at the top already first.
                      possible-parent
                      (element possible-parent))))

(defn root
  "This selector takes no args, it simply is the selector function. It returns
   the zip-loc of the root node (the HTML element)."
  [hzip-loc]
  (if (= :html (-> (zip/node hzip-loc) :tag))
    hzip-loc))

(defn find-in-text
  "Returns a function that takes a zip-loc argument and returns the zip-loc
   passed in iff it has some text node in its contents that matches the regular
   expression. Note that this only applies to the direct text content of a node;
   nodes which have the given text in one of their child nodes will not be
   selected."
  [re]
  (fn [hzip-loc]
    (when (some #(re-find re %)
                (->> (zip/node hzip-loc)
                     :content
                     (filter string?)))
      hzip-loc)))

(defn n-moves-until
  "This selector returns a selector function that selects its argument if
   that argument is some \"distance\" from a \"boundary.\" This is an abstract
   way of phrasing it, but it captures the full generality.

   The selector this function returns will apply the move argument to its own
   output, beginning with its zipper loc argument, until the term-pred argument
   called on its output returns true. At that point, the number of times the
   move function was called successfully is compared to kn+c; if there exists
   some value of k such that the two quantities are equal, then the selector
   will return the argument zipper loc successfully.

   For example, (n-moves-until 2 1 clojure.zip/left nil?) will return a selector
   that calls zip/left on its own output, beginning with the argument zipper
   loc, until its return value is nil (nil? returns true). Suppose it called
   left 5 times before zip/left returned nil. Then the selector will return
   with success, since 2k+1 = 5 for k = 2.

   Most nth-child-* selectors in this package use n-moves-until in their
   implementation."
  [n c move term-pred]
  (fn [hzip-loc]
    (let [distance (count-until move hzip-loc term-pred)]
      (if (== 0 n)
        ;; No stride, so distance must = c to select.
        (if (== distance c)
          hzip-loc)
        ;; There's a stride, so need to subtract c and see if the
        ;; remaining distance is a multiple of n.
        (if (== 0 (rem (- distance c) n))
          hzip-loc)))))

(defn nth-of-type
  "Returns a function that returns true if the node is the nth child of
   its parent (and it has a parent) of the given tag type. First element is 1,
   last is n."
  ([c typ]
   (cond (= :odd c)
         (nth-of-type 2 1 typ)
         (= :even c)
         (nth-of-type 2 0 typ)
         :else
         (nth-of-type 0 c typ)))
  ([n c typ]
   (fn [hzip-loc]
       ;; We're only interested in elements whose parents are also elements,
       ;; so check this up front and maybe save some work.
     (if (clojure.core/and (element-child hzip-loc)
                           (= typ (:tag (zip/node hzip-loc))))
       (let [sel (n-moves-until n c
                                #(left-pred % (fn [x] (-> (zip/node x)
                                                          :tag
                                                          (= typ))))
                                nil?)]
         (sel hzip-loc))))))

(defn nth-last-of-type
  "Returns a function that returns true if the node is the nth last child of
   its parent (and it has a parent) of the given tag type. First element is 1,
   last is n."
  ([c typ]
   (cond (= :odd c)
         (nth-last-of-type 2 1 typ)
         (= :even c)
         (nth-last-of-type 2 0 typ)
         :else
         (nth-last-of-type 0 c typ)))
  ([n c typ]
   (fn [hzip-loc]
       ;; We're only interested in elements whose parents are also elements,
       ;; so check this up front and maybe save some work.
     (if (clojure.core/and (element-child hzip-loc)
                           (= typ (:tag (zip/node hzip-loc))))
       (let [sel (n-moves-until n c
                                #(right-pred % (fn [x] (-> (zip/node x)
                                                           :tag
                                                           (= typ))))
                                nil?)]
         (sel hzip-loc))))))

(defn nth-child
  "Returns a function that returns true if the node is the nth child of
   its parent (and it has a parent). First element is 1, last is n."
  ([c]
   (cond (= :odd c)
         (nth-child 2 1)
         (= :even c)
         (nth-child 2 0)
         :else
         (nth-child 0 c)))
  ([n c]
   (fn [hzip-loc]
       ;; We're only interested in elements whose parents are also elements,
       ;; so check this up front and maybe save some work.
     (if (element-child hzip-loc)
       (let [sel (n-moves-until n c #(left-of-node-type % :element) nil?)]
         (sel hzip-loc))))))

(defn nth-last-child
  "Returns a function that returns true if the node has n siblings after it,
   and has a parent."
  ([c]
   (cond (= :odd c)
         (nth-last-child 2 1)
         (= :even c)
         (nth-last-child 2 0)
         :else
         (nth-last-child 0 c)))
  ([n c]
   (fn [hzip-loc]
       ;; We're only interested in elements whose parents are also elements,
       ;; so check this up front and maybe save some work.
     (if (element-child hzip-loc)
       (let [sel (n-moves-until n c #(right-of-node-type % :element) nil?)]
         (sel hzip-loc))))))

(defn first-child
  "This selector takes no args, it is simply the selector. Returns
   true if the node is the first child of its parent (and it has a
   parent)."
  [hzip-loc]
  (clojure.core/and (element-child hzip-loc)
                    ((nth-child 1) hzip-loc)))

(defn last-child
  "This selector takes no args, it is simply the selector. Returns
   true if the node is the last child of its parent (and it has a
   parent."
  [hzip-loc]
  (clojure.core/and (element-child hzip-loc)
                    ((nth-last-child 1) hzip-loc)))

;;
;; Selector combinators
;;

(defn and
  "Takes any number of selectors and returns a selector that is true if
   all of the argument selectors are true."
  [& selectors]
  (fn [zip-loc]
    (if (every? #(% zip-loc) selectors)
      zip-loc)))

(defn or
  "Takes any number of selectors and returns a selector that is true if
   any of the argument selectors are true."
  [& selectors]
  (fn [zip-loc]
    (if (some #(% zip-loc) selectors)
      zip-loc)))

(defn not
  "Takes a selector argument and returns a selector that is true if
   the underlying selector is false on its argument, and vice versa."
  [selector]
  (fn [hzip-loc]
    (if (clojure.core/not (selector hzip-loc))
      hzip-loc)))

(defn el-not
  "Takes a selector argument and returns a selector that is true if
   the underlying selector is false on its argument and vice versa, and
   additionally that argument is an element node. Compared to the 'not'
   selector, this corresponds more closely to the CSS equivalent, which
   will only ever select elements."
  [selector]
  (and (node-type :element)
       (not selector)))

(defn compose-unary
  "Takes a unary selection function and any number of selectors and returns
   a selector which returns true when each selector and the unary function
   applied to each subsequenct selector returns true.

   Example:
   (compose-unary has-child (tag :div) (class :foo) (attr :disabled))
   Produces the equivalent of:
   (and (tag :div)
        (has-child (and (class :foo)
                        (has-child (and (attr :disabled))))))"
  [unary-selector-fn & selectors]
  (let [rev (reverse selectors)]
    (loop [selectors (rest rev)
           output (and (first rev))]
      (cond
        (empty? selectors) output
        (= (count selectors) 1) (and (first selectors) (unary-selector-fn output))
        :else (recur (rest selectors)
                     (and (first selectors) (unary-selector-fn output)))))))

(defn ordered-adjacent
  "Takes a zipper movement function and any number of selectors as arguments
   and returns a selector that returns true when the zip-loc given as the
   argument is satisfied by the first selector, and the zip-loc arrived at by
   applying the move-fn argument is satisfied by the second selector, and so
   on for all the selectors given as arguments. If the move-fn
   moves to nil before the full selector list is satisfied, the entire
   selector fails, but note that success is checked before a move to nil is
   checked, so satisfying the last selector with the last node you can move
   to succeeds."
  [move-fn & selectors]
  ;; We'll work backwards through the selector list with an index. First we'll
  ;; build the selector list into an array for quicker access. We'll do it
  ;; immediately and then closure-capture the result, so it does not get
  ;; redone every time the selector is called.
  (let [selectors (into-array IFn selectors)]
    (fn [hzip-loc]
      (loop [curr-loc hzip-loc
             idx 0]
        (cond (>= idx (count selectors))
              hzip-loc ;; Got to end satisfying selectors, return the loc.
              (nil? curr-loc)
              nil ;; Ran off a boundary before satisfying selectors, return nil.
              :else
              (if-let [next-loc ((nth selectors idx) curr-loc)]
                (recur (move-fn next-loc)
                       (inc idx))))))))

(defn child
  "Takes any number of selectors as arguments and returns a selector that
   returns true when the zip-loc given as the argument is at the end of
   a chain of direct child relationships specified by the selectors given as
   arguments.

   Example: (child (tag :div) (class :foo) (attr :disabled))
     will select the input in
   <div><span class=\"foo\"><input disabled></input></span></div>
     but not in
   <div><span class=\"foo\"><b><input disabled></input></b></span></div>"
  [& selectors]
  (apply ordered-adjacent zip/up (reverse selectors)))

(defn has-child
  "Takes a selector as argument and returns a selector that returns true
   when some direct child node of the zip-loc given as the argument satisfies
   the selector.

   Example: (has-child (tag :div))
     will select only the inner span in
   <div><span><div></div></span></div>"
  [selector]
  (fn [hzip-loc]
    (let [subtree-start-loc (-> hzip-loc zip/down)
          has-children? (not= nil subtree-start-loc)]
      ;; has-children? is needed to guard against zip/* receiving a nil arg in
      ;; a selector.
      (if has-children?
        (if (select-next-loc selector subtree-start-loc
                             zip/right
                             #(nil? %))
          hzip-loc)))))

(defn parent
  "Takes any number of selectors as arguments and returns a selector that
   returns true when the zip-loc given as the argument is at the start of
   a chain of direct child relationships specified by the selectors given
   as arguments.

   Example: (parent (tag :div) (class :foo) (attr :disabled))
     will select the div in
   <div><span class=\"foo\"><input disabled></input></span></div>
     but not in
   <div><span class=\"foo\"><b><input disabled></input></b></span></div>"
  [& selectors]
  (apply compose-unary has-child selectors))

(defn follow-adjacent
  "Takes any number of selectors as arguments and returns a selector that
   returns true when the zip-loc given as the argument is at the end of
   a chain of direct element sibling relationships specified by the selectors
   given as arguments.

   Example: (follow-adjacent (tag :div) (class :foo))
     will select the span in
   <div>...</div><span class=\"foo\">...</span>
     but not in
   <div>...</div><b>...</b><span class=\"foo\">...</span>"
  [& selectors]
  (apply ordered-adjacent
         #(left-of-node-type % :element)
         (reverse selectors)))

(defn precede-adjacent
  "Takes any number of selectors as arguments and returns a selector that
   returns true when the zip-loc given as the argument is at the beginning of
   a chain of direct element sibling relationships specified by the selectors
   given as arguments.

   Example: (precede-adjacent (tag :div) (class :foo))
     will select the div in
   <div>...</div><span class=\"foo\">...</span>
     but not in
   <div>...</div><b>...</b><span class=\"foo\">...</span>"
  [& selectors]
  (apply ordered-adjacent
         #(right-of-node-type % :element)
         selectors))

(defn ordered
  "Takes a zipper movement function and any number of selectors as arguments
   and returns a selector that returns true when the zip-loc given as the
   argument is satisfied by the first selector, and some zip-loc arrived at by
   applying the move-fn argument *one or more times* is satisfied by the second
   selector, and so on for all the selectors given as arguments. If the move-fn
   moves to nil before a the full selector list is satisfied, the entire
   selector fails, but note that success is checked before a move to nil is
   checked, so satisfying the last selector with the last node you can move
   to succeeds."
  [move-fn & selectors]
  ;; This function is a lot like ordered-adjacent, above, but:
  ;; 1) failing to fulfill a selector does not stop us moving along the tree
  ;; 2) therefore, we need to make sure the first selector matches the loc under
  ;;    consideration, and not merely one that is farther along the movement
  ;;    direction.
  (let [selectors (into-array IFn selectors)]
    (fn [hzip-loc]
      ;; First need to check that the first selector matches the current loc,
      ;; or else we can return nil immediately.
      (let [fst-selector (nth selectors 0)]
        (if (fst-selector hzip-loc)
          ;; First selector matches this node, so now check along the
          ;; movement direction for the rest of the selectors.
          (loop [curr-loc (move-fn hzip-loc)
                 idx 1]
            (cond (>= idx (count selectors))
                  hzip-loc ;; Satisfied all selectors, so return the orig. loc.
                  (nil? curr-loc)
                  nil ;; Ran out of movements before selectors, return nil.
                  :else
                  (if ((nth selectors idx) curr-loc)
                    (recur (move-fn curr-loc)
                           (inc idx))
                    ;; Failed, so move but retry the same selector
                    (recur (move-fn curr-loc) idx)))))))))

(defn descendant
  "Takes any number of selectors as arguments and returns a selector that
   returns true when the zip-loc given as the argument is at the end of
   a chain of descendant relationships specified by the
   selectors given as arguments. To be clear, the node selected matches
   the final selector, but the previous selectors can match anywhere in
   the node's ancestry, provided they match in the order they are given
   as arguments, from top to bottom.

   Example: (descendant (tag :div) (class :foo) (attr :disabled))
     will select the input in both
   <div><span class=\"foo\"><input disabled></input></span></div>
     and
   <div><span class=\"foo\"><b><input disabled></input></b></span></div>"
  [& selectors]
  (apply ordered zip/up (reverse selectors)))

(defn has-descendant
  "Takes a selector as argument and returns a selector that returns true
   when some descendant node of the zip-loc given as the argument satisfies
   the selector.

   Be aware that because this selector must do a full sub-tree search on
   each node examined, it can have terrible performance. It's helpful if this is
   a late clause in an `and`, to prevent it from even attempting to match
   unless other criteria have been met first.

   Example: (has-descendant (tag :div))
     will select the span and the outer div, but not the inner div, in
   <span><div><div></div></div></span>"
  [selector]
  (fn [hzip-loc]
    ;; Want to not count the current node, and stop after the last node
    ;; in the subtree of it has been checked, which is the next node
    ;; after the rightmost child.
    (let [subtree-start-loc (-> hzip-loc zip/down)
          has-children? (not= nil subtree-start-loc)]
      ;; has-children? is needed to guard against zip/* receiving a nil arg in
      ;; a selector.
      (if has-children?
        (let [subtree-end-loc (after-subtree hzip-loc)]
          (if (select-next-loc selector subtree-start-loc
                               zip/next
                               #(= % subtree-end-loc))
            hzip-loc))))))

(defn ancestor
  "Takes any number of selectors as arguments and returns a selector that
   returns true when the zip-loc given as the argument is at the start of
   a chain of descendant relationships specified by the selectors given
   as arguments; intervening elements that do not satisfy a selector are
   simply ignored and do not prevent a match.

   Example: (ancestor (tag :div) (class :foo) (attr :disabled))
     will select the div in both
   <div><span class=\"foo\"><input disabled></input></span></div>
     and
   <div><span class=\"foo\"><b><input disabled></input></b></span></div>"
  [& selectors]
  (apply compose-unary has-descendant selectors))

(defn follow
  "Takes any number of selectors as arguments and returns a selector that
   returns true when the zip-loc given as the argument is at the end of
   a chain of element sibling relationships specified by the selectors
   given as arguments; intervening elements that do not satisfy a selector
   are simply ignored and do not prevent a match.

   Example: (follow (tag :div) (class :foo))
     will select the span in both
   <div>...</div><span class=\"foo\">...</span>
     and
   <div>...</div><b>...</b><span class=\"foo\">...</span>"
  [& selectors]
  (apply ordered #(left-of-node-type % :element) (reverse selectors)))

(defn precede
  "Takes any number of selectors as arguments and returns a selector that
   returns true when the zip-loc given as the argument is at the beginning of
   a chain of element sibling relationships specified by the selectors
   given as arguments; intervening elements that do not satisfy a selector
   are simply ignored and do not prevent a match.

   Example: (precede (tag :div) (class :foo))
     will select the div in both
   <div>...</div><span class=\"foo\">...</span>
     and
   <div>...</div><b>...</b><span class=\"foo\">...</span>"
  [& selectors]
  (apply ordered #(right-of-node-type % :element) selectors))
(ns hickory.hiccup-utils
  "Utilities for working with hiccup forms."
  (:require [clojure.string :as str]))

(defn- first-idx
  "Given two possible indexes, returns the lesser that is not -1. If both
   are -1, then -1 is returned. Useful for searching strings for multiple
   markers, as many routines will return -1 for not found.

   Examples: (first-idx -1 -1) => -1
             (first-idx -1 2) => 2
             (first-idx 5 -1) => 5
             (first-idx 5 3) => 3"
  #?(:clj  [^long a ^long b]
     :cljs [a b])
  (if (== a -1)
    b
    (if (== b -1)
      a
      (min a b))))

(defn- index-of
  ([^String s c]
   #?(:clj  (.indexOf s (int c))
      :cljs (.indexOf s c)))
  ([^String s c idx]
   #?(:clj  (.indexOf s (int c) (int idx))
      :cljs (.indexOf s c idx))))

(defn- split-keep-trailing-empty
  "clojure.string/split is a wrapper on java.lang.String/split with the limit
   parameter equal to 0, which keeps leading empty strings, but discards
   trailing empty strings. This makes no sense, so we have to write our own
   to keep the trailing empty strings."
  [s re]
  (str/split s re -1))

(defn tag-well-formed?
  "Given a hiccup tag element, returns true iff the tag is in 'valid' hiccup
   format. Which in this function means:
      1. Tag name is non-empty.
      2. If there is an id, there is only one.
      3. If there is an id, it is nonempty.
      4. If there is an id, it comes before any classes.
      5. Any class name is nonempty."
  [tag-elem]
  (let [tag-elem (name tag-elem)
        hash-idx (int (index-of tag-elem \#))
        dot-idx (int (index-of tag-elem \.))
        tag-cutoff (first-idx hash-idx dot-idx)]
    (and (< 0 (count tag-elem)) ;; 1.
         (if (== tag-cutoff -1) true (> tag-cutoff 0)) ;; 1.
         (if (== hash-idx -1)
           true
           (and (== -1 (index-of tag-elem \# (inc hash-idx))) ;; 2.
                (< (inc hash-idx) (first-idx (index-of tag-elem \. ;; 3.
                                                       (inc hash-idx))
                                             (count tag-elem)))))
         (if (and (not= hash-idx -1) (not= dot-idx -1)) ;; 4.
           (< hash-idx dot-idx)
           true)
         (if (== dot-idx -1) ;; 5.
           true
           (let [classes (.substring tag-elem (inc dot-idx))]
             (every? #(< 0 (count %))
                     (split-keep-trailing-empty classes #"\.")))))))

(defn tag-name
  "Given a well-formed hiccup tag element, return just the tag name as
  a string."
  [tag-elem]
  (let [tag-elem (name tag-elem)
        hash-idx (int (index-of tag-elem \#))
        dot-idx (int (index-of tag-elem \.))
        cutoff (first-idx hash-idx dot-idx)]
    (if (== cutoff -1)
      ;; No classes or ids, so the entire tag-element is the name.
      tag-elem
      ;; There was a class or id, so the tag name ends at the first
      ;; of those.
      (.substring tag-elem 0 cutoff))))

(defn class-names
  "Given a well-formed hiccup tag element, return a vector containing
   any class names included in the tag, as strings. Ignores the hiccup
   requirement that any id on the tag must come
   first. Example: :div.foo.bar => [\"foo\" \"bar\"]."
  [tag-elem]
  (let [tag-elem (name tag-elem)]
    (loop [curr-dot (index-of tag-elem \.)
           classes (transient [])]
      (if (== curr-dot -1)
        ;; Didn't find another dot, so no more classes.
        (persistent! classes)
        ;; There's another dot, so there's another class.
        (let [next-dot (index-of tag-elem \. (inc curr-dot))
              next-hash (index-of tag-elem \# (inc curr-dot))
              cutoff (first-idx next-dot next-hash)]
          (if (== cutoff -1)
            ;; Rest of the tag element is the last class.
            (recur next-dot
                   (conj! classes (.substring tag-elem (inc curr-dot))))
            ;; The current class name is terminated by another element.
            (recur next-dot
                   (conj! classes
                          (.substring tag-elem (inc curr-dot) cutoff)))))))))

(defn id
  "Given a well-formed hiccup tag element, return a string containing
   the id, or nil if there isn't one."
  [tag-elem]
  (let [tag-elem (name tag-elem)
        hash-idx (int (index-of tag-elem \#))
        next-dot-idx (int (index-of tag-elem \. hash-idx))]
    (if (== hash-idx -1)
      nil
      (if (== next-dot-idx -1)
        (.substring tag-elem (inc hash-idx))
        (.substring tag-elem (inc hash-idx) next-dot-idx)))))

(defn- expand-content-seqs
  "Given a sequence of hiccup forms, presumably the content forms of another
   hiccup element, return a new sequence with any sequence elements expanded
   into the main sequence. This logic does not apply recursively, so sequences
   inside sequences won't be expanded out. Also note that this really only
   applies to sequences; things that seq? returns true on. So this excludes
   vectors.
     (expand-content-seqs [1 '(2 3) (for [x [1 2 3]] (* x 2)) [5]])
     ==> (1 2 3 2 4 6 [5])"
  [content]
  (loop [remaining-content content
         result (transient [])]
    (if (nil? remaining-content)
      (persistent! result)
      (if (seq? (first remaining-content))
        (recur (next remaining-content)
               ;; Fairly unhappy with this nested loop, but it seems
               ;; necessary to continue the handling of transient vector.
               (loop [remaining-seq (first remaining-content)
                      result result]
                 (if (nil? remaining-seq)
                   result
                   (recur (next remaining-seq)
                          (conj! result (first remaining-seq))))))
        (recur (next remaining-content)
               (conj! result (first remaining-content)))))))

(defn- normalize-element
  "Given a well-formed hiccup form, ensure that it is in the form
     [tag attributes content1 ... contentN].
   That is, an unadorned tag name (keyword, lowercase), all attributes in the
   attribute map in the second element, and then any children. Note that this
   does not happen recursively; content is not modified."
  [hiccup-form]
  (let [[tag-elem & content] hiccup-form]
    (when (not (tag-well-formed? tag-elem))
      (throw (ex-info (str "Invalid input: Tag element"
                           tag-elem "is not well-formed.")
                      {})))
    (let [tag-name (keyword (str/lower-case (tag-name tag-elem)))
          tag-classes (class-names tag-elem)
          tag-id (id tag-elem)
          tag-attrs {:id tag-id
                     :class (if (not (empty? tag-classes))
                              (str/join " " tag-classes))}
          [map-attrs content] (if (map? (first content))
                                [(first content) (rest content)]
                                [nil content])
          ;; Note that we replace tag attributes with map attributes, without
          ;; merging them. This is to match hiccup's behavior.
          attrs (merge tag-attrs map-attrs)]
      (apply vector tag-name attrs content))))

(defn normalize-form
  "Given a well-formed hiccup form, recursively normalizes it, so that it and
   all children elements will also be normalized. A normalized form is in the
   form
     [tag attributes content1 ... contentN].
   That is, an unadorned tag name (keyword, lowercase), all attributes in the
   attribute map in the second element, and then any children. Any content
   that is a sequence is also expanded out into the main sequence of content
   items."
  [form]
  (if (string? form)
    form
    ;; Do a pre-order walk and save the first two items, then do the children,
    ;; then glue them back together.
    (let [[tag attrs & contents] (normalize-element form)]
      (apply vector tag attrs (map #(if (vector? %)
                                      ;; Recurse only on vec children.
                                      (normalize-form %)
                                      %)
                                   (expand-content-seqs contents))))))
(ns hickory.render
  (:require [hickory.hiccup-utils :as hu]
            [hickory.utils :as utils]
            [clojure.string :as str]))

;;
;; Hickory to HTML
;;

(defn- render-hickory-attribute
  "Given a map entry m, representing the attribute name and value, returns a
   string representing that key/value pair as it would be rendered into HTML."
  [m]
  (str " " (name (key m)) "=\"" (utils/html-escape (val m)) "\""))

(defn hickory-to-html
  "Given a hickory HTML DOM map structure (as returned by as-hickory), returns a
   string containing HTML it represents. Keep in mind this function is not super
   fast or heavy-duty.

   Note that it will NOT in general be the case that

     (= my-html-src (hickory-to-html (as-hickory (parse my-html-src))))

   as we do not keep any letter case or whitespace information, any
   \"tag-soupy\" elements, attribute quote characters used, etc."
  [dom]
  (if (string? dom)
    (utils/html-escape dom)
    (try
      (case (:type dom)
        :document
        (apply str (map hickory-to-html (:content dom)))
        :document-type
        (utils/render-doctype (get-in dom [:attrs :name])
                              (get-in dom [:attrs :publicid])
                              (get-in dom [:attrs :systemid]))
        :element
        (cond
          (utils/void-element (:tag dom))
          (str "<" (name (:tag dom))
               (apply str (map render-hickory-attribute (:attrs dom)))
               ">")
          (utils/unescapable-content (:tag dom))
          (str "<" (name (:tag dom))
               (apply str (map render-hickory-attribute (:attrs dom)))
               ">"
               (apply str (:content dom)) ;; Won't get html-escaped.
               "</" (name (:tag dom)) ">")
          :else
          (str "<" (name (:tag dom))
               (apply str (map render-hickory-attribute (:attrs dom)))
               ">"
               (apply str (map hickory-to-html (:content dom)))
               "</" (name (:tag dom)) ">"))
        :comment
        (str "<!--" (apply str (:content dom)) "-->"))
      (catch #?(:clj  IllegalArgumentException
                :cljs js/Error) e
        (throw
         (if (utils/starts-with #?(:clj (.getMessage e) :cljs (.-message e)) "No matching clause: ")
           (ex-info (str "Not a valid node: " (pr-str dom)) {:dom dom})
           e))))))

;;
;; Hiccup to HTML
;;

(defn- render-hiccup-attrs
  "Given a hiccup attribute map, returns a string containing the attributes
   rendered as they should appear in an HTML tag, right after the tag (including
   a leading space to separate from the tag, if any attributes present)."
  [attrs]
  ;; Hiccup normally does not html-escape strings, but it does for attribute
  ;; values.
  (let [attrs-str (->> (for [[k v] attrs]
                         (cond (true? v)
                               (str (name k))
                               (nil? v)
                               ""
                               :else
                               (str (name k) "=" "\"" (utils/html-escape v) "\"")))
                       (filter #(not (empty? %)))
                       sort
                       (str/join " "))]
    (if (not (empty? attrs-str))
      ;; If the attrs-str is not "", we need to pad the front so that the
      ;; tag will separate from the attributes. Otherwise, "" is fine to return.
      (str " " attrs-str)
      attrs-str)))

(declare hiccup-to-html)
(defn- render-hiccup-element
  "Given a normalized hiccup element (such as the output of
   hickory.hiccup-utils/normalize-form; see this function's docstring
   for more detailed definition of a normalized hiccup element), renders
   it to HTML and returns it as a string."
  [n-element]
  (let [[tag attrs & content] n-element]
    (if (utils/void-element tag)
      (str "<" (name tag) (render-hiccup-attrs attrs) ">")
      (str "<" (name tag) (render-hiccup-attrs attrs) ">"
           (hiccup-to-html content)
           "</" (name tag) ">"))))

(defn- render-hiccup-form
  "Given a normalized hiccup form (such as the output of
   hickory.hiccup-utils/normalize-form; see this function's docstring
   for more detailed definition of a normalized hiccup form), renders
   it to HTML and returns it as a string."
  [n-form]
  (if (vector? n-form)
    (render-hiccup-element n-form)
    n-form))

(defn hiccup-to-html
  "Given a sequence of hiccup forms (as returned by as-hiccup), returns a
   string containing HTML it represents. Keep in mind this function is not super
   fast or heavy-duty, and definitely not a replacement for dedicated hiccup
   renderers, like hiccup itself, which *is* fast and heavy-duty.

```klipse
  (hiccup-to-html '([:html {} [:head {}] [:body {} [:a {} \"foo\"]]]))
```

   Note that it will NOT in general be the case that

     (= my-html-src (hiccup-to-html (as-hiccup (parse my-html-src))))

   as we do not keep any letter case or whitespace information, any
   \"tag-soupy\" elements, attribute quote characters used, etc. It will also
   not generally be the case that this function's output will exactly match
   hiccup's.
   For instance:

```klipse
(hiccup-to-html (as-hiccup (parse \"<A href=\\\"foo\\\">foo</A>\")))
```
  "
  [hiccup-forms]
  (apply str (map #(render-hiccup-form (hu/normalize-form %)) hiccup-forms)))

(ns hickory.convert
  "Functions to convert from one representation to another."
  (:require [hickory.render :as render]
            [hickory.core :as core]
            [hickory.utils :as utils]))

(defn hiccup-to-hickory
  "Given a sequence of hiccup forms representing a full document,
   returns an equivalent hickory node representation of that document.
   This will perform HTML5 parsing as a full document, no matter what
   it is given.

   Note that this function is heavyweight: it requires a full HTML
   re-parse to work."
  [hiccup-forms]
  (core/as-hickory (core/parse (render/hiccup-to-html hiccup-forms))))

(defn hiccup-fragment-to-hickory
  "Given a sequence of hiccup forms representing a document fragment,
   returns an equivalent sequence of hickory fragments.

   Note that this function is heavyweight: it requires a full HTML
   re-parse to work."
  [hiccup-forms]
  (map core/as-hickory
       (core/parse-fragment (render/hiccup-to-html hiccup-forms))))

(defn hickory-to-hiccup
  "Given a hickory format dom object, returns an equivalent hiccup
   representation. This can be done directly and exactly, but in general
   you will not be able to go back from the hiccup."
  [dom]
  (if (string? dom)
    (utils/html-escape dom)
    (case (:type dom)
      :document
      (mapv hickory-to-hiccup (:content dom))
      :document-type
      (utils/render-doctype (get-in dom [:attrs :name])
                            (get-in dom [:attrs :publicid])
                            (get-in dom [:attrs :systemid]))
      :element
      (if (utils/unescapable-content (:tag dom))
        (if (every? string? (:content dom))
          ;; Merge :attrs contents with {} to prevent nil from getting into
          ;; the hiccup forms when there are no attributes.
          (apply vector (:tag dom) (into {} (:attrs dom)) (:content dom))
          (throw (ex-info
                  "An unescapable content tag had non-string children."
                  {:error-location dom})))
        (apply vector (:tag dom) (into {} (:attrs dom))
               (map hickory-to-hiccup (:content dom))))
      :comment
      (str "<!--" (apply str (:content dom)) "-->"))))

(ns hickory-bundle)
(def as-hiccup hickory.core/as-hiccup)
(def as-hickory hickory.core/as-hickory)
(def parse hickory.core/parse)
(def parse-fragment hickory.core/parse-fragment)

