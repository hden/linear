Linear
---

docs:

* @docs/architecture.md
* @bb.edn (frequently used commands)
* plan/spec ドキュメントは `docs/plans/`・`docs/specs/` にローカル保存し、Git 管理しない

tips:

* domain model が中心にいて、周りが capability を提供する構成を重要視
* 破壊的変更を恐れるな。後方互換性は不要
* 我々がコントロール出来るものは malli の instrumentation で十分。malli の instrumentation で十分な部分は実行時のバリデーションは不要。どうしても実行時にバリデーションせざるを得ない部分のみバリデーションする

do's & dont's:

* Public `defn` APIs use an arg-map or `[target arg-map]`; map destructuring or the name `arg-map` identifies map arguments. Zero/one-argument value operations are allowed.
* `bb lint` checks each public arity in `src`, `test`, and `scripts`. Positional/variadic exceptions require a qualified var, arity, and reason in `.clj-kondo/config.edn`; do not exempt namespaces.
* Global Var replacement (`with-redefs`, `with-redefs-fn`, `alter-var-root`, or direct Var root mutation) is forbidden without exceptions. Test through application capabilities or real adapter APIs.
* Tests for verification, lint, architecture, setup scripts, or hooks are forbidden, regardless of placement. Do not write or move these tests into `test/`. Application tests must remain and assert behavior at domain, adapter, and transport boundaries.
* The Stop hook runs `bb lint`: deterministic static checks for source policies, dependency direction, public APIs, and lint findings. It must not load application code, start databases, build images, or fetch dependencies. Source policies are enforced independently of lint suppression.
* CI runs `bb verify`, adding source loading, formatting, and the complete application test suite. During development, validate changed behavior with the relevant application tests.

* 主張は全て hard-evidence によって裏付ける、逆に hard-evidence がない主張は hallucination と見なす
* 再利用する必要がない　schema は named にせず inline で十分
* 副作用と目的としない関数には bang (!) をつけない
* dynamic でもないのに earmuff (*) をつけない
* `foo*` みたいに * を suffix につけない
* リソース解放は `with-open` / `try` ... `finally` で保証する。解放のために `catch Throwable` を導入しない
* `catch` は回復・変換などの処理が必要な例外だけに使う。仮想的な障害への対応で捕捉範囲や例外管理を増やさない（YAGNI）

refs:

* https://eli.thegreenplace.net/2016/the-expression-problem-and-its-solutions/
* https://duct-framework.org/docs/
* https://github.com/clj-kondo/clj-kondo/blob/master/doc/config.md
* https://github.com/metosin/malli/tree/master
* https://github.com/tursodatabase/turso
  * https://github.com/tursodatabase/libsql/blob/main/docs/HRANA_3_SPEC.md
  * https://github.com/tursodatabase/libsql/blob/main/docs/HTTP_V2_SPEC.md
