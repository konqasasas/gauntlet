# Dependencies

本プラグインは自作Javaソースです。旧試作のJavaソースや参考プロジェクトのコードは移植していません。
既存プロジェクトから引き継いだものはGradleラッパーとビルド補助設定です。

| 依存 | 用途 | 配布JARへの同梱 |
|---|---|---|
| Paper API 26.3.build.166-beta | コンパイル時API。サーバーが実行時に提供 | なし |
| Gson 2.14.0 | JSON状態保存。Paperが実行時に提供。Apache-2.0 | なし |
| JUnit Jupiter 5.11.4 | 開発時の自動テスト。EPL-2.0 | なし |
| Gradle 9.8.1 wrapper | ビルド補助。Apache-2.0 | なし |

PaperサーバーはMinecraft本体とは別の実行環境として `run` に配置します。Paperプロジェクト内のライセンス表示とMinecraft EULAに従って利用してください。
新しい自作コードの公開ライセンスはまだ指定していません。
