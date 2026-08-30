import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'opencray_motion.dart';
import 'opencray_palette.dart';
import 'opencray_tokens.dart';

/// Placeholder blocks for content that has not arrived yet.
///
/// A spinner only says "something is happening"; a skeleton says *what* is
/// coming and roughly how much of it, so the page stops jumping when the data
/// lands. Build the shape of the real content out of [OpenCraySkeletonBar] and
/// wrap the whole group in one [OpenCraySkeletonPulse]: a controller per bar
/// would make the group breathe out of phase and read as noise.

/// Fades a group of skeleton bars in and out on a single ticker.
///
/// The pulse is the only animation in the group, and it carries the semantics
/// label for the region it stands in — the bars themselves are decoration and
/// are hidden from the accessibility tree.
class OpenCraySkeletonPulse extends StatefulWidget {
  const OpenCraySkeletonPulse({
    super.key,
    required this.child,
    this.semanticsLabel,
  });

  final Widget child;

  /// Announced in place of the bars while the content loads. Leave it null only
  /// where the surrounding widget already announces the wait.
  final String? semanticsLabel;

  @override
  State<OpenCraySkeletonPulse> createState() => _OpenCraySkeletonPulseState();
}

class _OpenCraySkeletonPulseState extends State<OpenCraySkeletonPulse>
    with SingleTickerProviderStateMixin {
  static const Duration _period = Duration(milliseconds: 1100);
  static const double _restOpacity = 1;
  static const double _dipOpacity = 0.46;

  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: _period,
  );
  late final Animation<double> _opacity =
      Tween<double>(begin: _restOpacity, end: _dipOpacity).animate(
        CurvedAnimation(parent: _controller, curve: Curves.easeInOut),
      );

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Holding the controller mid-tween keeps the bars visible at a settled
    // opacity when the platform asks for less motion, without a second branch
    // in build.
    if (OpenCrayMotion.reduce(context)) {
      _controller.stop();
      _controller.value = 0.5;
    } else if (!_controller.isAnimating) {
      _controller.repeat(reverse: true);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: widget.semanticsLabel,
      child: ExcludeSemantics(
        child: FadeTransition(opacity: _opacity, child: widget.child),
      ),
    );
  }
}

/// One placeholder block: a fixed [width], a [widthFactor] of the slot it sits
/// in, or full width when neither is given.
class OpenCraySkeletonBar extends StatelessWidget {
  const OpenCraySkeletonBar({
    super.key,
    required this.height,
    this.width,
    this.widthFactor,
    this.radius,
    this.color,
  }) : assert(
         width == null || widthFactor == null,
         'A bar takes a fixed width or a fraction of its slot, not both.',
       );

  final double height;
  final double? width;
  final double? widthFactor;
  final BorderRadius? radius;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final Widget bar = SizedBox(
      width: width,
      height: height,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: color ?? context.palette.surfaceMuted,
          borderRadius:
              radius ??
              BorderRadius.circular(math.min(height / 2, OpenCraySpacing.xs)),
        ),
      ),
    );
    if (widthFactor == null) {
      return bar;
    }
    return Align(
      alignment: AlignmentDirectional.centerStart,
      child: FractionallySizedBox(widthFactor: widthFactor, child: bar),
    );
  }
}

/// Row shaped like the app's list tiles: an optional [leading] block, a title
/// line with a shorter meta line under it, and an optional [trailing] block.
class OpenCraySkeletonListRow extends StatelessWidget {
  const OpenCraySkeletonListRow({
    super.key,
    this.padding = const EdgeInsets.symmetric(
      horizontal: OpenCraySpacing.md,
      vertical: 14,
    ),
    this.leading,
    this.trailing,
    this.titleWidthFactor = 0.54,
    this.metaWidthFactor = 0.3,
    this.titleHeight = 14,
    this.metaHeight = 10,
    this.lineGap = 6,
  });

  final EdgeInsetsGeometry padding;
  final Widget? leading;
  final Widget? trailing;
  final double titleWidthFactor;
  final double metaWidthFactor;
  final double titleHeight;
  final double metaHeight;
  final double lineGap;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: padding,
      child: Row(
        children: <Widget>[
          if (leading != null) ...<Widget>[
            leading!,
            const SizedBox(width: OpenCraySpacing.sm),
          ],
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                OpenCraySkeletonBar(
                  height: titleHeight,
                  widthFactor: titleWidthFactor,
                ),
                SizedBox(height: lineGap),
                OpenCraySkeletonBar(
                  height: metaHeight,
                  widthFactor: metaWidthFactor,
                ),
              ],
            ),
          ),
          if (trailing != null) ...<Widget>[
            const SizedBox(width: OpenCraySpacing.sm),
            trailing!,
          ],
        ],
      ),
    );
  }
}

/// Card that mirrors the app's standard list card — surface fill, hairline
/// border, 16dp radius — holding [rows] separated by the divider the real list
/// draws between its tiles.
class OpenCraySkeletonCard extends StatelessWidget {
  const OpenCraySkeletonCard({
    super.key,
    required this.rows,
    this.padding = EdgeInsets.zero,
    this.dividerIndent = 0,
  });

  final List<Widget> rows;
  final EdgeInsetsGeometry padding;
  final double dividerIndent;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: context.palette.surface,
        borderRadius: const BorderRadius.all(OpenCrayRadii.lg),
        border: Border.all(color: context.palette.divider),
        boxShadow: context.palette.cardShadow,
      ),
      child: Padding(
        padding: padding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            for (int index = 0; index < rows.length; index += 1) ...<Widget>[
              rows[index],
              if (index < rows.length - 1)
                Divider(
                  height: 1,
                  color: context.palette.divider,
                  indent: dividerIndent,
                  endIndent: dividerIndent,
                ),
            ],
          ],
        ),
      ),
    );
  }
}
