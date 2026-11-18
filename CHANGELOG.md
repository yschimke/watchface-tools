# Changelog

## [0.3.2](https://github.com/yschimke/watchface-tools/compare/v0.3.1...v0.3.2) (2026-11-18)


### Bug Fixes

* **wff-packager:** publish Java 17 bytecode and a 17 jvm.version attribute ([#28](https://github.com/yschimke/watchface-tools/issues/28)) ([f67d710](https://github.com/yschimke/watchface-tools/commit/f67d710faec43ecf4df41b79cb81f05543059458))

## [0.3.1](https://github.com/yschimke/watchface-tools/compare/v0.3.0...v0.3.1) (2026-11-18)


### Bug Fixes

* **wff-core:** publish Java 17 bytecode for the JVM target ([#19](https://github.com/yschimke/watchface-tools/issues/19)) ([9f704bd](https://github.com/yschimke/watchface-tools/commit/9f704bd827624b452c40b2f3c8eca2378e7b59ea))

## [0.3.0](https://github.com/yschimke/watchface-tools/compare/v0.2.1...v0.3.0) (2026-11-18)


### Features

* **wff-core:** cite WFF minSdk table, check xs:sequence order, expose required-children query ([#17](https://github.com/yschimke/watchface-tools/issues/17)) ([e23bb43](https://github.com/yschimke/watchface-tools/commit/e23bb433f8b33c3b22185db19255da72f8b5786f))

## [0.2.1](https://github.com/yschimke/watchface-tools/compare/v0.2.0...v0.2.1) (2026-11-18)


### Bug Fixes

* validate values, expressions and data sources; fail packaging on Google XSD findings ([#14](https://github.com/yschimke/watchface-tools/issues/14)) ([bfa28b5](https://github.com/yschimke/watchface-tools/commit/bfa28b514981188c3a7cb3beebf1273653371e4c))
* **wff-core:** collapse XML whitespace in numeric values and reject NaN for bounded numbers ([#16](https://github.com/yschimke/watchface-tools/issues/16)) ([41b5681](https://github.com/yschimke/watchface-tools/commit/41b56810d8f6235c51b8e1a3d09442c4adf51aaa))

## [0.2.0](https://github.com/yschimke/watchface-tools/compare/v0.1.0...v0.2.0) (2026-11-18)


### Features

* **packager:** sign with a shared key from a secret and pin its certificate ([#9](https://github.com/yschimke/watchface-tools/issues/9)) ([2746373](https://github.com/yschimke/watchface-tools/commit/27463736a68436083ad563a79b890cc41e59964f))

## 0.1.0 (2026-11-17)


### Features

* add :wff-packager to build signed, validated Watch Face Push APKs ([#2](https://github.com/yschimke/watchface-tools/issues/2)) ([5fcf223](https://github.com/yschimke/watchface-tools/commit/5fcf2239132b17e2da9ea2d5f2f4d1436479542a))
* add Wear OS push app that installs site designs via Watch Face Push ([#4](https://github.com/yschimke/watchface-tools/issues/4)) ([6221c33](https://github.com/yschimke/watchface-tools/commit/6221c33f97934f0aaf92ceb7a33b57ee40ba16e4))
* wff-core — lossless Watch Face Format model, XML writer/parser, XSD-generated v1–v5 schema ([#1](https://github.com/yschimke/watchface-tools/issues/1)) ([54fc4b7](https://github.com/yschimke/watchface-tools/commit/54fc4b7214259196eee09b7402005af84c77f06e))


### Bug Fixes

* **ci:** publish the BOM when only its own build script changed ([#8](https://github.com/yschimke/watchface-tools/issues/8)) ([dddeaeb](https://github.com/yschimke/watchface-tools/commit/dddeaebd43db26c47b063bcb6f5c001b85ad6df6))
