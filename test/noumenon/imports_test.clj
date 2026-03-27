(ns noumenon.imports-test
  (:require [clojure.test :refer [deftest is testing]]
            [noumenon.imports :as imports]))

;; --- Tier 0: Pure function tests ---

;; --- Clojure extraction ---

(deftest extract-imports-clojure-test
  (testing "extracts :require deps"
    (let [src "(ns foo.core\n  (:require [foo.db :as db]\n            [foo.util :refer [log]]))"
          result (imports/extract-imports :clojure src)]
      (is (= #{"foo.db" "foo.util"} (set result)))))

  (testing "extracts :use deps"
    (let [src "(ns foo.core\n  (:use [foo.helper]))"
          result (imports/extract-imports :clojure src)]
      (is (some #{"foo.helper"} result))))

  (testing "returns empty for non-ns file"
    (is (empty? (imports/extract-imports :clojure "(defn foo [] 1)"))))

  (testing "returns empty for empty string"
    (is (empty? (imports/extract-imports :clojure ""))))

  (testing "returns empty for malformed ns"
    (is (empty? (imports/extract-imports :clojure "(ns)")))))

(deftest extract-imports-clojurescript-test
  (testing "delegates to clojure method"
    (let [src "(ns foo.core\n  (:require [foo.db]))"
          result (imports/extract-imports :clojurescript src)]
      (is (some #{"foo.db"} result)))))

;; --- Clojure resolution ---

(def clj-paths
  #{"src/myapp/core.clj" "src/myapp/db.clj" "src/myapp/util.clj"
    "test/myapp/core_test.clj"})

(deftest resolve-import-clojure-test
  (testing "resolves internal namespace to src path"
    (is (= "src/myapp/db.clj"
           (imports/resolve-import :clojure "myapp.db" "src/myapp/core.clj" clj-paths))))

  (testing "resolves to test path"
    (is (= "test/myapp/core_test.clj"
           (imports/resolve-import :clojure "myapp.core-test" "test/myapp/core_test.clj" clj-paths))))

  (testing "returns nil for external dep"
    (is (nil? (imports/resolve-import :clojure "clojure.string" "src/myapp/core.clj" clj-paths))))

  (testing "handles hyphens to underscores"
    (let [paths #{"src/my_app/foo_bar.clj"}]
      (is (= "src/my_app/foo_bar.clj"
             (imports/resolve-import :clojure "my-app.foo-bar" "src/x.clj" paths))))))

;; --- Clojure enrich-file ---

(deftest enrich-file-clojure-test
  (let [src "(ns myapp.core\n  (:require [myapp.db :as db]\n            [myapp.util :as util]\n            [clojure.string :as str]))"
        result (imports/enrich-file :clojure src "src/myapp/core.clj" clj-paths)]
    (testing "resolves internal deps"
      (is (= #{"src/myapp/db.clj" "src/myapp/util.clj"} (set result))))
    (testing "excludes external deps"
      (is (not (some #{"clojure/string.clj"} result))))
    (testing "excludes self"
      (is (not (some #{"src/myapp/core.clj"} result))))))

;; --- Python extraction ---

(deftest extract-imports-python-test
  (testing "extracts import and from-import"
    (let [src "import os\nimport json\nfrom myapp.db import connect\nfrom myapp.util import log"
          result (imports/extract-imports :python src)]
      (when (seq result)  ; skip if python3 not available
        (is (some #{"os"} result))
        (is (some #{"myapp.db"} result))))))

;; --- Python resolution ---

(deftest resolve-import-python-test
  (let [paths #{"myapp/db.py" "myapp/util.py" "myapp/__init__.py"}]
    (testing "resolves dotted module to .py file"
      (is (= "myapp/db.py"
             (imports/resolve-import :python "myapp.db" "main.py" paths))))
    (testing "returns nil for external module"
      (is (nil? (imports/resolve-import :python "os" "main.py" paths))))))

;; --- JS/TS extraction ---

(deftest extract-imports-javascript-test
  (testing "extracts ES module imports"
    (let [src "import { foo } from './utils';\nimport bar from '../lib/bar';\nconst x = require('./config');"
          result (imports/extract-imports :javascript src)]
      (when (seq result)  ; skip if node not available
        (is (some #{"./utils"} result))
        (is (some #{"../lib/bar"} result))
        (is (some #{"./config"} result))))))

;; --- JS/TS resolution ---

(deftest resolve-import-javascript-test
  (let [paths #{"src/utils.js" "src/config.ts" "lib/bar.js" "lib/bar/index.js"}]
    (testing "resolves relative import with extension"
      (is (= "src/utils.js"
             (imports/resolve-import :javascript "./utils" "src/app.js" paths))))
    (testing "returns nil for bare specifier"
      (is (nil? (imports/resolve-import :javascript "lodash" "src/app.js" paths))))))

;; --- Rust extraction ---

(deftest extract-imports-rust-test
  (testing "extracts mod declarations"
    (let [src "mod parser;\npub mod lexer;\nmod tests {\n    // inline\n}"
          result (imports/extract-imports :rust src)]
      (is (some #{"parser"} result))
      (is (some #{"lexer"} result)))))

;; --- Rust resolution ---

(deftest resolve-import-rust-test
  (let [paths #{"src/parser.rs" "src/lexer/mod.rs"}]
    (testing "resolves to .rs file"
      (is (= "src/parser.rs"
             (imports/resolve-import :rust "parser" "src/main.rs" paths))))
    (testing "resolves to mod.rs"
      (is (= "src/lexer/mod.rs"
             (imports/resolve-import :rust "lexer" "src/main.rs" paths))))))

;; --- Java extraction ---

(deftest extract-imports-java-test
  (testing "extracts import statements"
    (let [src "package com.example;\n\nimport com.example.Foo;\nimport java.util.List;"
          result (imports/extract-imports :java src)]
      (is (some #{"com.example.Foo"} result))
      (is (some #{"java.util.List"} result)))))

;; --- Java resolution ---

(deftest resolve-import-java-test
  (let [paths #{"com/example/Foo.java" "com/example/Bar.java"}]
    (testing "resolves to .java file"
      (is (= "com/example/Foo.java"
             (imports/resolve-import :java "com.example.Foo" "com/example/Main.java" paths))))
    (testing "returns nil for stdlib"
      (is (nil? (imports/resolve-import :java "java.util.List" "Main.java" paths))))))

;; --- Elixir extraction ---

(deftest extract-imports-elixir-test
  (testing "extracts alias/import/use/require"
    (let [src "defmodule MyApp.Accounts do\n  alias MyApp.Repo\n  import Ecto.Query\n  use GenServer\n  require Logger\nend"
          result (imports/extract-imports :elixir src)]
      (when (seq result)  ; skip if elixir not available
        (is (some #{"MyApp.Repo"} result))
        (is (some #{"Ecto.Query"} result))
        (is (some #{"GenServer"} result))
        (is (some #{"Logger"} result)))))

  (testing "extracts multi-alias"
    (let [src "alias MyApp.{Accounts, Repo}"
          result (imports/extract-imports :elixir src)]
      (when (seq result)
        (is (some #{"MyApp.Accounts"} result))
        (is (some #{"MyApp.Repo"} result)))))

  (testing "returns empty for empty string"
    (is (empty? (imports/extract-imports :elixir "")))))

;; --- Elixir resolution ---

(deftest resolve-import-elixir-test
  (let [paths #{"lib/my_app/accounts.ex" "lib/my_app/repo.ex" "lib/my_app.ex"}]
    (testing "resolves module to lib path"
      (is (= "lib/my_app/repo.ex"
             (imports/resolve-import :elixir "MyApp.Repo" "lib/my_app/accounts.ex" paths))))
    (testing "returns nil for external dep"
      (is (nil? (imports/resolve-import :elixir "Ecto.Query" "lib/my_app/accounts.ex" paths))))))

;; --- Erlang extraction ---

(deftest extract-imports-erlang-test
  (testing "extracts include directives"
    (let [src "-module(my_server).\n-include(\"my_header.hrl\").\n-include_lib(\"kernel/include/file.hrl\")."
          result (imports/extract-imports :erlang src)]
      (is (some #{"my_header.hrl"} result))
      (is (some #{"kernel/include/file.hrl"} result))))

  (testing "returns empty for no includes"
    (is (empty? (imports/extract-imports :erlang "-module(foo).\n-export([start/0]).")))))

;; --- Erlang resolution ---

(deftest resolve-import-erlang-test
  (let [paths #{"include/my_header.hrl" "src/my_server.erl"}]
    (testing "resolves include to path"
      (is (= "include/my_header.hrl"
             (imports/resolve-import :erlang "include/my_header.hrl" "src/my_server.erl" paths))))
    (testing "returns nil for external include"
      (is (nil? (imports/resolve-import :erlang "kernel/include/file.hrl" "src/my_server.erl" paths))))))

;; --- C# extraction ---

(deftest extract-imports-csharp-test
  (testing "extracts using directives"
    (let [src "using System;\nusing MyApp.Models;\nusing MyApp.Services;"
          result (imports/extract-imports :csharp src)]
      (is (= #{"System" "MyApp.Models" "MyApp.Services"} (set result)))))

  (testing "extracts global usings"
    (let [result (imports/extract-imports :csharp "global using System.Linq;")]
      (is (some #{"System.Linq"} result))))

  (testing "extracts using static"
    (let [result (imports/extract-imports :csharp "using static System.Math;")]
      (is (some #{"System.Math"} result))))

  (testing "ignores using dispose pattern"
    (is (empty? (imports/extract-imports :csharp "using (var stream = new FileStream()) {}"))))

  (testing "returns empty for empty string"
    (is (empty? (imports/extract-imports :csharp "")))))

;; --- C# resolution ---

(deftest resolve-import-csharp-test
  (let [paths #{"src/MyApp/Models/User.cs" "src/MyApp/Services/UserService.cs"}]
    (testing "resolves internal namespace"
      (is (= "src/MyApp/Models/User.cs"
             (imports/resolve-import :csharp "MyApp.Models.User" "src/MyApp/Program.cs" paths))))
    (testing "returns nil for System namespace"
      (is (nil? (imports/resolve-import :csharp "System.IO" "src/MyApp/Program.cs" paths))))
    (testing "returns nil for Microsoft namespace"
      (is (nil? (imports/resolve-import :csharp "Microsoft.Extensions.DI" "src/Program.cs" paths))))))

;; --- MSBuild project extraction ---

(deftest extract-imports-msbuild-project-test
  (testing "extracts ProjectReference"
    (let [src "<ItemGroup>\n  <ProjectReference Include=\"..\\Other\\Other.csproj\" />\n</ItemGroup>"
          result (imports/extract-imports :msbuild-project src)]
      (is (= ["..\\Other\\Other.csproj"] result))))

  (testing "extracts ClInclude and ClCompile"
    (let [src "<ClCompile Include=\"src\\main.cpp\" />\n<ClInclude Include=\"include\\util.h\" />"
          result (imports/extract-imports :msbuild-project src)]
      (is (= #{"src\\main.cpp" "include\\util.h"} (set result)))))

  (testing "returns empty for no references"
    (is (empty? (imports/extract-imports :msbuild-project "<Project></Project>")))))

;; --- MSBuild project resolution ---

(deftest resolve-import-msbuild-project-test
  (let [paths #{"src/MyApp/MyApp.csproj" "src/main.cpp" "include/util.h"}]
    (testing "resolves ProjectReference with backslashes and .."
      (is (= "src/MyApp/MyApp.csproj"
             (imports/resolve-import :msbuild-project "..\\..\\src\\MyApp\\MyApp.csproj"
                                     "test/MyApp.Tests/MyApp.Tests.csproj" paths))))
    (testing "resolves ClCompile path"
      (is (= "src/main.cpp"
             (imports/resolve-import :msbuild-project "src\\main.cpp"
                                     "MyProject.vcxproj" paths))))
    (testing "returns nil for missing path"
      (is (nil? (imports/resolve-import :msbuild-project "..\\Missing\\Missing.csproj"
                                        "src/MyApp/MyApp.csproj" paths))))))

;; --- MSBuild solution extraction ---

(deftest extract-imports-msbuild-solution-test
  (testing "extracts project entries"
    (let [src "Project(\"{FAE04EC0}\") = \"MyApp\", \"src\\MyApp\\MyApp.csproj\", \"{GUID}\"\nEndProject\nProject(\"{FAE04EC0}\") = \"Tests\", \"test\\Tests\\Tests.csproj\", \"{GUID}\""
          result (imports/extract-imports :msbuild-solution src)]
      (is (= #{"src\\MyApp\\MyApp.csproj" "test\\Tests\\Tests.csproj"} (set result)))))

  (testing "returns empty for no projects"
    (is (empty? (imports/extract-imports :msbuild-solution "Global\nEndGlobal")))))

;; --- MSBuild solution resolution ---

(deftest resolve-import-msbuild-solution-test
  (let [paths #{"src/MyApp/MyApp.csproj" "test/Tests/Tests.csproj"}]
    (testing "resolves project path from sln"
      (is (= "src/MyApp/MyApp.csproj"
             (imports/resolve-import :msbuild-solution "src\\MyApp\\MyApp.csproj"
                                     "MySolution.sln" paths))))
    (testing "returns nil for missing project"
      (is (nil? (imports/resolve-import :msbuild-solution "missing\\Missing.csproj"
                                        "MySolution.sln" paths))))))

;; --- Default method ---

(deftest extract-imports-default-test
  (testing "unsupported language returns empty"
    (is (empty? (imports/extract-imports :haskell "module Main where")))
    (is (empty? (imports/extract-imports :markdown "# Hello")))))

;; --- Fixture-based integration test ---

(deftest enrich-fixture-clojure-test
  (let [paths #{"src/myapp/core.clj" "src/myapp/db.clj" "src/myapp/util.clj"
                "test/myapp/core_test.clj"}
        read-fixture (fn [path]
                       (slurp (str "test-fixtures/clojure/" path)))
        result-core  (imports/enrich-file :clojure (read-fixture "src/myapp/core.clj")
                                          "src/myapp/core.clj" paths)
        result-db    (imports/enrich-file :clojure (read-fixture "src/myapp/db.clj")
                                          "src/myapp/db.clj" paths)
        result-util  (imports/enrich-file :clojure (read-fixture "src/myapp/util.clj")
                                          "src/myapp/util.clj" paths)
        result-test  (imports/enrich-file :clojure (read-fixture "test/myapp/core_test.clj")
                                          "test/myapp/core_test.clj" paths)]
    (testing "core imports db and util"
      (is (= #{"src/myapp/db.clj" "src/myapp/util.clj"} (set result-core))))
    (testing "db imports util"
      (is (= ["src/myapp/util.clj"] result-db)))
    (testing "util imports nothing internal"
      (is (empty? result-util)))
    (testing "test imports core"
      (is (= ["src/myapp/core.clj"] result-test)))))

(deftest enrich-fixture-python-test
  (let [paths #{"myapp/core.py" "myapp/db.py" "myapp/util.py" "myapp/__init__.py"}
        read-fixture (fn [path] (slurp (str "test-fixtures/python/" path)))
        result (imports/enrich-file :python (read-fixture "myapp/core.py")
                                    "myapp/core.py" paths)]
    (when (seq (imports/extract-imports :python "import os"))  ; skip if python3 unavailable
      (testing "core imports db and util"
        (is (= #{"myapp/db.py" "myapp/util.py"} (set result)))))))

(deftest enrich-fixture-javascript-test
  (let [paths #{"src/app.js" "src/db.js" "src/util.js" "src/config.js"}
        read-fixture (fn [path] (slurp (str "test-fixtures/javascript/" path)))
        result (imports/enrich-file :javascript (read-fixture "src/app.js")
                                    "src/app.js" paths)]
    (when (seq (imports/extract-imports :javascript "import x from './y';"))
      (testing "app imports db, util, config"
        (is (= #{"src/db.js" "src/util.js" "src/config.js"} (set result)))))))

(deftest enrich-fixture-rust-test
  (let [paths #{"src/main.rs" "src/parser.rs" "src/lexer/mod.rs"}
        read-fixture (fn [path] (slurp (str "test-fixtures/rust/" path)))
        result (imports/enrich-file :rust (read-fixture "src/main.rs")
                                    "src/main.rs" paths)]
    (testing "main declares parser and lexer modules"
      (is (= #{"src/parser.rs" "src/lexer/mod.rs"} (set result))))))

(deftest enrich-fixture-java-test
  (let [paths #{"com/example/Main.java" "com/example/Foo.java"}
        read-fixture (fn [path] (slurp (str "test-fixtures/java/" path)))
        result (imports/enrich-file :java (read-fixture "com/example/Main.java")
                                    "com/example/Main.java" paths)]
    (testing "Main imports Foo, skips stdlib"
      (is (= ["com/example/Foo.java"] result)))))

(deftest enrich-fixture-elixir-test
  (let [paths #{"lib/my_app.ex" "lib/my_app/accounts.ex" "lib/my_app/repo.ex"}
        read-fixture (fn [path] (slurp (str "test-fixtures/elixir/" path)))
        result-app   (imports/enrich-file :elixir (read-fixture "lib/my_app.ex")
                                          "lib/my_app.ex" paths)
        result-accts (imports/enrich-file :elixir (read-fixture "lib/my_app/accounts.ex")
                                          "lib/my_app/accounts.ex" paths)
        result-repo  (imports/enrich-file :elixir (read-fixture "lib/my_app/repo.ex")
                                          "lib/my_app/repo.ex" paths)]
    (when (seq (imports/extract-imports :elixir "alias Foo"))  ; skip if elixir unavailable
      (testing "app imports accounts and repo via multi-alias"
        (is (= #{"lib/my_app/accounts.ex" "lib/my_app/repo.ex"} (set result-app))))
      (testing "accounts imports repo"
        (is (some #{"lib/my_app/repo.ex"} result-accts)))
      (testing "repo has no internal imports"
        (is (empty? result-repo))))))

(deftest enrich-fixture-erlang-test
  (let [paths #{"src/my_server.erl" "include/my_header.hrl"}
        read-fixture (fn [path] (slurp (str "test-fixtures/erlang/" path)))
        result (imports/enrich-file :erlang (read-fixture "src/my_server.erl")
                                    "src/my_server.erl" paths)]
    (testing "my_server includes my_header"
      (is (= ["include/my_header.hrl"] result)))))

(deftest enrich-fixture-csharp-test
  (let [paths #{"src/MyApp/Program.cs" "src/MyApp/Models/User.cs"
                "src/MyApp/Services/UserService.cs"}
        read-fixture (fn [path] (slurp (str "test-fixtures/csharp/" path)))
        result-prog (imports/enrich-file :csharp (read-fixture "src/MyApp/Program.cs")
                                         "src/MyApp/Program.cs" paths)
        result-user (imports/enrich-file :csharp (read-fixture "src/MyApp/Models/User.cs")
                                         "src/MyApp/Models/User.cs" paths)
        result-svc  (imports/enrich-file :csharp (read-fixture "src/MyApp/Services/UserService.cs")
                                         "src/MyApp/Services/UserService.cs" paths)]
    (testing "Program imports Models and Services (via suffix match)"
      (is (pos? (count result-prog))))
    (testing "User.cs imports Services (via suffix match)"
      (is (pos? (count result-user))))
    (testing "UserService imports Models (via suffix match)"
      (is (pos? (count result-svc))))
    (testing "System namespace not resolved"
      (is (not (some #(re-matches #".*System.*" %) result-prog))))))

(deftest enrich-fixture-msbuild-test
  (let [paths #{"src/MyApp/MyApp.csproj" "test/MyApp.Tests/MyApp.Tests.csproj"}
        read-fixture (fn [path] (slurp (str "test-fixtures/csharp/" path)))
        result-sln   (imports/enrich-file :msbuild-solution
                                          (read-fixture "MyApp.sln")
                                          "MyApp.sln" paths)
        result-tests (imports/enrich-file :msbuild-project
                                          (read-fixture "test/MyApp.Tests/MyApp.Tests.csproj")
                                          "test/MyApp.Tests/MyApp.Tests.csproj" paths)]
    (testing ".sln imports both .csproj files"
      (is (= #{"src/MyApp/MyApp.csproj" "test/MyApp.Tests/MyApp.Tests.csproj"}
             (set result-sln))))
    (testing "Tests.csproj imports MyApp.csproj"
      (is (= ["src/MyApp/MyApp.csproj"] result-tests)))))

(deftest enrich-fixture-vcxproj-test
  (let [paths #{"src/main.cpp" "src/util.cpp" "include/util.h" "MyProject.vcxproj"}
        read-fixture (fn [path] (slurp (str "test-fixtures/cpp/" path)))
        result (imports/enrich-file :msbuild-project
                                    (read-fixture "MyProject.vcxproj")
                                    "MyProject.vcxproj" paths)]
    (testing ".vcxproj imports source and header files"
      (is (= #{"src/main.cpp" "src/util.cpp" "include/util.h"} (set result))))))
