import 'package:flutter/material.dart';

final class OpenCrayMotion {
  static const Duration instant = Duration(milliseconds: 110);
  static const Duration micro = Duration(milliseconds: 160);
  static const Duration quick = Duration(milliseconds: 180);
  static const Duration expand = Duration(milliseconds: 240);
  static const Duration panel = Duration(milliseconds: 260);
  static const Duration page = Duration(milliseconds: 300);
  static const Duration pageExit = Duration(milliseconds: 240);

  static const Curve enter = Curves.easeOutCubic;
  static const Curve exit = Curves.easeInCubic;
  static const Curve spatial = Curves.easeOutCubic;
  static const Curve expandCurve = Curves.easeInOutCubic;

  static bool reduce(BuildContext context) {
    final mediaQuery = MediaQuery.maybeOf(context);
    return mediaQuery?.disableAnimations == true ||
        mediaQuery?.accessibleNavigation == true;
  }

  static Duration resolve(BuildContext context, Duration duration) =>
      reduce(context) ? Duration.zero : duration;

  static AnimationStyle sheetAnimationStyle(BuildContext context) {
    return AnimationStyle(
      duration: resolve(context, panel),
      reverseDuration: resolve(context, pageExit),
      curve: enter,
      reverseCurve: exit,
    );
  }

  const OpenCrayMotion._();
}

enum OpenCrayRouteDirection { fromRight, fromLeft }

PageRoute<T> openCrayHorizontalPageRoute<T>({
  required WidgetBuilder builder,
  RouteSettings? settings,
  OpenCrayRouteDirection direction = OpenCrayRouteDirection.fromRight,
}) {
  return PageRouteBuilder<T>(
    settings: settings,
    transitionDuration: OpenCrayMotion.page,
    reverseTransitionDuration: OpenCrayMotion.pageExit,
    pageBuilder: (context, animation, secondaryAnimation) => builder(context),
    transitionsBuilder: (context, animation, secondaryAnimation, child) {
      final curved = CurvedAnimation(
        parent: animation,
        curve: OpenCrayMotion.enter,
        reverseCurve: OpenCrayMotion.exit,
      );
      if (OpenCrayMotion.reduce(context)) {
        return FadeTransition(opacity: curved, child: child);
      }
      final double startX = switch (direction) {
        OpenCrayRouteDirection.fromRight => 1,
        OpenCrayRouteDirection.fromLeft => -1,
      };
      return FadeTransition(
        opacity: Tween<double>(begin: 0.94, end: 1).animate(curved),
        child: SlideTransition(
          position: Tween<Offset>(
            begin: Offset(startX, 0),
            end: Offset.zero,
          ).animate(curved),
          child: child,
        ),
      );
    },
  );
}

class OpenCrayDirectionalIndexedStack extends StatefulWidget {
  const OpenCrayDirectionalIndexedStack({
    super.key,
    required this.index,
    required this.children,
  });

  final int index;
  final List<Widget> children;

  @override
  State<OpenCrayDirectionalIndexedStack> createState() =>
      _OpenCrayDirectionalIndexedStackState();
}

class _OpenCrayDirectionalIndexedStackState
    extends State<OpenCrayDirectionalIndexedStack>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: OpenCrayMotion.page,
  )..value = 1;
  late int _displayedIndex = widget.index;
  int? _previousIndex;
  int _direction = 1;

  @override
  void didUpdateWidget(OpenCrayDirectionalIndexedStack oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.index == _displayedIndex) {
      return;
    }
    _direction = widget.index > _displayedIndex ? 1 : -1;
    _previousIndex = _displayedIndex;
    _displayedIndex = widget.index;
    if (OpenCrayMotion.reduce(context)) {
      _previousIndex = null;
      _controller.value = 1;
      return;
    }
    _controller
      ..duration = OpenCrayMotion.page
      ..forward(from: 0);
  }

  @override
  void initState() {
    super.initState();
    _controller.addStatusListener((status) {
      if (status == AnimationStatus.completed && mounted) {
        setState(() {
          _previousIndex = null;
        });
      }
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (widget.children.isEmpty) {
      return const SizedBox.shrink();
    }
    final int safeIndex = widget.index.clamp(0, widget.children.length - 1);
    if (OpenCrayMotion.reduce(context)) {
      return IndexedStack(index: safeIndex, children: widget.children);
    }
    return Stack(
      fit: StackFit.expand,
      children: [
        for (int index = 0; index < widget.children.length; index += 1)
          _buildLayer(index, safeIndex, widget.children[index]),
      ],
    );
  }

  Widget _buildLayer(int index, int safeIndex, Widget child) {
    final bool isCurrent = index == safeIndex;
    final bool isPrevious = index == _previousIndex;
    if (!isCurrent && !isPrevious) {
      return Offstage(
        offstage: true,
        child: TickerMode(enabled: false, child: child),
      );
    }
    return Positioned.fill(
      child: IgnorePointer(
        ignoring: !isCurrent,
        child: TickerMode(
          enabled: isCurrent,
          child: _buildTransition(index, child),
        ),
      ),
    );
  }

  Widget _buildTransition(int index, Widget child) {
    final bool isPrevious = index == _previousIndex;
    if (_controller.value == 1 && !isPrevious) {
      return child;
    }
    return AnimatedBuilder(
      animation: _controller,
      child: child,
      builder: (context, child) {
        final double t = OpenCrayMotion.spatial.transform(_controller.value);
        final double offsetX = isPrevious
            ? -_direction * 0.055 * t
            : _direction * 0.08 * (1 - t);
        final double opacity = isPrevious ? 1 - (0.16 * t) : 0.92 + (0.08 * t);
        return FractionalTranslation(
          translation: Offset(offsetX, 0),
          child: Opacity(opacity: opacity, child: child),
        );
      },
    );
  }
}

class OpenCrayDirectionalSwitcher extends StatelessWidget {
  const OpenCrayDirectionalSwitcher({
    super.key,
    required this.activeKey,
    required this.direction,
    required this.child,
  });

  final Key activeKey;
  final int direction;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final reduce = OpenCrayMotion.reduce(context);
    return AnimatedSwitcher(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.page),
      reverseDuration: OpenCrayMotion.resolve(context, OpenCrayMotion.pageExit),
      switchInCurve: OpenCrayMotion.enter,
      switchOutCurve: OpenCrayMotion.exit,
      transitionBuilder: (child, animation) {
        if (reduce) {
          return FadeTransition(opacity: animation, child: child);
        }
        final bool incoming = child.key == activeKey;
        final double startX = incoming ? direction * 0.10 : -direction * 0.055;
        return FadeTransition(
          opacity: Tween<double>(
            begin: incoming ? 0.94 : 0.84,
            end: 1,
          ).animate(animation),
          child: SlideTransition(
            position: Tween<Offset>(
              begin: Offset(startX, 0),
              end: Offset.zero,
            ).animate(animation),
            child: child,
          ),
        );
      },
      child: KeyedSubtree(key: activeKey, child: child),
    );
  }
}
