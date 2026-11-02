// TextScaler 来自 flutter/painting（由 material.dart 一并导出），不是 dart:ui。
import 'package:flutter/widgets.dart' show TextScaler;

/// 在系统字号基础上再乘一个系数（聊天页的「字号微调」）。
///
/// 继承 [TextScaler] 而不是每次自己算：`Text` 会把它传给排版层，
/// 界面上所有文字（含 `RichText`、Markdown 渲染）一起缩放，
/// 不会出现只有一部分文字变大的割裂。
///
/// 必须实现 `==` / `hashCode`：Flutter 会用它判断 `Text` 是否需要重排版，
/// 不实现的话每帧都认为是新对象，长列表会被拖慢。
class ChatTextScaler extends TextScaler {
  const ChatTextScaler(this.base, this.factor);

  /// 系统（或外层 `MediaQuery`）给定的缩放。
  final TextScaler base;

  /// 本页额外乘上的系数。
  final double factor;

  @override
  double scale(double fontSize) => base.scale(fontSize) * factor;

  @override
  double get textScaleFactor => scale(1);

  @override
  bool operator ==(Object other) =>
      other is ChatTextScaler && other.base == base && other.factor == factor;

  @override
  int get hashCode => Object.hash(base, factor);
}
