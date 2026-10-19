# 第三方组件声明

本项目（MiGallerySync）以 MIT 许可证发布。项目在构建与运行过程中使用了下列第三方组件，
它们各自的版权归其作者所有，并按其各自许可证的条款分发。此处列出的是**随 APK 一起分发**
或**参与构建**的组件。

## 随 APK 分发的组件（Apache License 2.0）

以下组件被编译进 APK，分发时须保留其版权声明与许可证副本：

| 组件 | 版本 | 许可证 | 项目地址 |
| --- | --- | --- | --- |
| AndroidX Core (core-ktx) | 1.19.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX Activity (activity-compose) | 1.13.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX HeifWriter | 1.1.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX ExifInterface | 1.4.2 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| libxposed service | 102.0.0 | Apache-2.0 | https://github.com/libxposed/service |
| Miuix（miuix-ui / miuix-preference / miuix-icons） | 0.9.4 | Apache-2.0 | https://github.com/miuix-kotlin-multiplatform/miuix |

Apache License 2.0 全文：https://www.apache.org/licenses/LICENSE-2.0

## 仅参与构建、不随 APK 分发的组件

| 组件 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| libxposed api | 102.0.0 | Apache-2.0 | 编译期接口（compileOnly） |
| Android Gradle Plugin | 9.1.1 | Apache-2.0 | 构建工具 |
| Kotlin / Kotlin Compose 编译器插件 | 2.4.20 | Apache-2.0 | 构建工具 |
| Gradle | 见 gradle/wrapper | Apache-2.0 | 构建工具 |

## 仅用于测试的组件（不随 APK 分发）

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 |
| AndroidX Test Ext JUnit | 1.3.0 | Apache-2.0 |
| AndroidX Test Espresso | 3.7.0 | Apache-2.0 |

测试期依赖不进入发布产物，仅在本仓库的单元测试与仪器测试中使用。

## 关于与小米相册的互操作

本项目通过反射调用小米相册（`com.miui.gallery`）的运行时接口实现功能，**不包含、不复制**
小米相册的任何源代码或资源文件。小米、MIUI、HyperOS 等名称与商标归小米公司所有，
本项目与小米公司无任何关联。
