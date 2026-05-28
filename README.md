# Speed Volume Control

走行速度（GPS）に応じてメディア音量を自動調整する Android アプリ

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF.svg)](https://kotlinlang.org/)
[![minSdk: 26](https://img.shields.io/badge/minSdk-26-green.svg)]()
[![UI: Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)

## 概要

運転中はスピードが上がるほどロードノイズや風切り音が大きくなり、音楽やナビの音声が聞き取りづらくなります。かといって信号待ちで止まったときに音量が大きすぎると今度はうるさい。

このアプリは **GPS から取得した走行速度に応じて、メディア音量を自動で上げ下げ** します。速度と音量の対応関係はユーザーが自由に設定でき、急に音量が変わらないよう滑らかに追従させることもできます。

## デモ

デモ画像は準備中です。下記に追加予定:

![メイン画面](docs/screenshot-main.png)
![設定画面](docs/screenshot-settings.png)

## 主な機能

- **速度連動の音量自動調整** — GPS 速度をリアルタイムに取得し、メディア音量（`STREAM_MUSIC`）を自動制御します。
- **2ポイント / 3ポイントの設定モード** — シンプルに「最低速度↔最高速度」の2点で決める方式と、中間点を加えてより細かくカーブを描く3点方式を切り替えられます。
- **音量カーブのカスタマイズ** — 速度と音量の間を線形ではなく **べき乗カーブ（curve power）** で補間でき、「低速ではあまり上げず、高速で一気に上げる」といった味付けが可能です。
- **滑らかな音量遷移** — 目標音量へ一気に飛ばさず、変化レートに応じてアニメーションさせることで、急な音量変化による不快感を防ぎます（即時反映も選択可）。
- **フォアグラウンドサービスでの常時動作** — アプリを前面に出していなくても、フォアグラウンドサービスとして GPS 取得と音量制御を継続します。
- **Picture-in-Picture（PiP）対応** — 他アプリ（音楽プレーヤーやナビ）を使いながら、現在速度と音量を小窓で確認できます。
- **GPS 状態の可視化** — GPS 信号の取得状況（取得中 / 信号なし）を表示し、一定時間信号が途切れた場合を検知します。
- **設定の永続化** — 設定値は端末に保存され、次回起動時にも引き継がれます。

## 仕組み / アーキテクチャ

このアプリの中心は、**位置情報フォアグラウンドサービスと音量制御ロジックの分離** です。

```
┌──────────────┐    Broadcast (速度 / GPS状態 / ログ)   ┌─────────────────────────────┐
│  MainActivity │ ◄───────────────────────────────────── │ LocationForegroundService    │
│  (UI / PiP)   │                                         │  ・GPSから速度を取得          │
│               │ ─── 設定値 (Intent Extra) ───────────► │  ・速度→音量を算出            │
└──────────────┘                                         │  ・AudioManagerで音量を反映   │
                                                          └─────────────────────────────┘
```

### 速度から音量への変換

設定モード（2点 / 3点）に応じて、現在速度が属するセグメントを判定し、そのセグメント内での位置を 0〜1 に正規化したうえで **べき乗カーブ** を適用して音量を補間します。

```
normalized = (現在速度 - 区間下限速度) / (区間上限速度 - 区間下限速度)
curved     = normalized ^ curvePower
音量(%)     = 下限音量 + (上限音量 - 下限音量) × curved
```

`curvePower` を変えることで、同じ速度域でも音量の立ち上がり方を調整できます。

### 滑らかな音量遷移

算出した目標音量へ即座に飛ばすと耳障りなため、変化レートが一定未満のときは `ValueAnimator` で目標値まで補間し、`AudioManager` の音量ステップが実際に変わるタイミングだけ反映してチャタリングを抑えています。

## 技術スタック

| 項目 | 内容 |
|---|---|
| 言語 | Kotlin |
| UI フレームワーク | Jetpack Compose（Material 3） |
| minSdk | 26（Android 8.0 / Oreo） |
| targetSdk / compileSdk | 35（Android 15） |
| Java / Kotlin JVM target | 11 |
| 位置情報 | フォアグラウンドサービス（`foregroundServiceType="location"`） |
| 音量制御 | `AudioManager`（`STREAM_MUSIC`）/ `ValueAnimator` |
| 設定保存 | SharedPreferences |
| 通知 | Android 13 以降の通知権限に対応 |

## 必要な権限

| 権限 | 用途 |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 走行速度を得るための位置情報取得 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | バックグラウンドでも位置情報取得を継続するため |
| `POST_NOTIFICATIONS` | フォアグラウンドサービス通知の表示（Android 13 以降） |

## ビルド方法

1. リポジトリをクローン
   ```
   git clone https://github.com/ya-ma-n-1972/speed-volume-control.git
   ```

2. Android Studio（最新の Stable 推奨）でプロジェクトフォルダを開く

3. 必要な SDK 等の確認
   - compileSdk 35（Android 15）
   - Kotlin / Jetpack Compose 対応版の Android Studio

4. 実機（GPS が使える Android 端末）を接続してビルド・実行

> 速度（位置情報）を使うアプリのため、エミュレータでは動作確認が難しい場合があります。**実機での確認を推奨**します。

## APK のダウンロード

ビルド済みの APK は [Releases](https://github.com/ya-ma-n-1972/speed-volume-control/releases) からダウンロードできます。

インストール時は、Android の設定で「提供元不明のアプリ」のインストールを許可する必要があります。

## ライセンス

このプロジェクトは [MIT License](LICENSE) のもとで公開されています。
