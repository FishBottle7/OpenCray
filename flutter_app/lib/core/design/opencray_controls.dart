import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'opencray_motion.dart';
import 'opencray_palette.dart';
import 'opencray_tokens.dart';

/// Shared interactive controls for the OpenCray design language.
///
/// Switches, segmented pickers and selection marks live here so every feature
/// animates state changes the same way instead of hand-rolling a toggle that
/// snaps between states.

/// Decoration for a text field that already supplies its own frame — the Files
/// and Skills search bars, the chat composer, the inline editors in settings.
///
/// `border: InputBorder.none` on its own is not enough. [InputDecorator] reads
/// the painted border from `enabledBorder` / `focusedBorder` before it looks at
/// `border`, and those two arrive from the theme, so the field draws a second
/// outline inside the container it was meant to sit flush in. `filled` behaves
/// the same way: the theme's surface fill lands on top of the host container's
/// own fill, which shows up the moment that container is tinted. Reach for this
/// with `copyWith` instead of repeating six border slots per call site.
const InputDecoration openCrayBareInputDecoration = InputDecoration(
  filled: false,
  border: InputBorder.none,
  enabledBorder: InputBorder.none,
  focusedBorder: InputBorder.none,
  disabledBorder: InputBorder.none,
  errorBorder: InputBorder.none,
  focusedErrorBorder: InputBorder.none,
);

/// Rounded surface that lets descendant [InkWell]s paint their ripple on top of
/// the card they live in. Without it the ripple is added to the shell
/// [Material] and ends up hidden behind the card background.
class OpenCrayInkSurface extends StatelessWidget {
  const OpenCrayInkSurface({
    super.key,
    required this.child,
    this.borderRadius = const BorderRadius.all(OpenCrayRadii.lg),
  });

  final Widget child;
  final BorderRadius borderRadius;

  @override
  Widget build(BuildContext context) {
    return Material(
      type: MaterialType.transparency,
      borderRadius: borderRadius,
      clipBehavior: Clip.antiAlias,
      child: child,
    );
  }
}

/// Track-and-thumb switch with a sliding thumb, press feedback and a light
/// haptic tick. Sized to match the Material switch footprint it replaces so
/// swapping it in does not reflow rows.
class OpenCraySwitch extends StatefulWidget {
  const OpenCraySwitch({
    super.key,
    required this.value,
    required this.onChanged,
    this.semanticLabel,
  });

  final bool value;
  final ValueChanged<bool>? onChanged;
  final String? semanticLabel;

  @override
  State<OpenCraySwitch> createState() => _OpenCraySwitchState();
}

class _OpenCraySwitchState extends State<OpenCraySwitch> {
  bool _pressed = false;

  void _setPressed(bool pressed) {
    if (_pressed == pressed) {
      return;
    }
    setState(() {
      _pressed = pressed;
    });
  }

  void _handleTap() {
    final ValueChanged<bool>? onChanged = widget.onChanged;
    if (onChanged == null) {
      return;
    }
    HapticFeedback.selectionClick();
    onChanged(!widget.value);
  }

  @override
  Widget build(BuildContext context) {
    final bool enabled = widget.onChanged != null;
    final OpenCrayPalette palette = context.palette;
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.quick,
    );
    final Color trackColor;
    if (!enabled) {
      trackColor = widget.value
          ? palette.primary.withValues(alpha: 0.32)
          : palette.surfaceMuted;
    } else {
      trackColor = widget.value ? palette.primary : palette.surfaceSunken;
    }
    return Semantics(
      container: true,
      enabled: enabled,
      toggled: widget.value,
      label: widget.semanticLabel,
      onTap: enabled ? _handleTap : null,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: enabled ? _handleTap : null,
        onTapDown: enabled ? (_) => _setPressed(true) : null,
        onTapUp: enabled ? (_) => _setPressed(false) : null,
        onTapCancel: enabled ? () => _setPressed(false) : null,
        child: SizedBox(
          width: OpenCraySizes.switchHitWidth,
          height: OpenCraySizes.switchHitHeight,
          child: Center(child: _buildTrack(context, duration, trackColor)),
        ),
      ),
    );
  }

  Widget _buildTrack(BuildContext context, Duration duration, Color track) {
    final bool enabled = widget.onChanged != null;
    final OpenCrayPalette palette = context.palette;
    return AnimatedContainer(
      duration: duration,
      curve: OpenCrayMotion.enter,
      width: OpenCraySizes.switchTrackWidth,
      height: OpenCraySizes.switchTrackHeight,
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: track,
        borderRadius: const BorderRadius.all(OpenCrayRadii.pill),
      ),
      child: AnimatedAlign(
        duration: duration,
        curve: OpenCrayMotion.enter,
        alignment: widget.value ? Alignment.centerRight : Alignment.centerLeft,
        child: AnimatedScale(
          duration: OpenCrayMotion.resolve(context, OpenCrayMotion.instant),
          curve: OpenCrayMotion.enter,
          scale: _pressed ? 0.88 : 1,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: enabled
                  ? palette.controlThumb
                  : palette.controlThumbDisabled,
              shape: BoxShape.circle,
              boxShadow: <BoxShadow>[
                BoxShadow(
                  color: palette.shadowInk.withValues(alpha: 0x24 / 0xFF),
                  offset: const Offset(0, 1),
                  blurRadius: 3,
                ),
              ],
            ),
            child: const SizedBox.square(
              dimension: OpenCraySizes.switchThumbSize,
            ),
          ),
        ),
      ),
    );
  }
}

/// Segmented picker whose selection indicator slides between segments instead
/// of cross-fading in place. Index based so duplicate labels stay addressable.
class OpenCraySegmentedControl extends StatelessWidget {
  const OpenCraySegmentedControl({
    super.key,
    required this.labels,
    required this.selectedIndex,
    this.onSelected,
    this.textStyle,
    this.verticalPadding = 8,
  });

  final List<String> labels;
  final int selectedIndex;
  final ValueChanged<int>? onSelected;
  final TextStyle? textStyle;
  final double verticalPadding;

  @override
  Widget build(BuildContext context) {
    if (labels.isEmpty) {
      return const SizedBox.shrink();
    }
    final int count = labels.length;
    final int activeIndex = selectedIndex.clamp(0, count - 1);
    final double thumbX = count == 1 ? 0 : -1 + 2 * activeIndex / (count - 1);
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.quick,
    );
    final TextStyle base =
        textStyle ??
        const TextStyle(fontSize: 12, height: 16 / 12, letterSpacing: -0.1);
    final OpenCrayPalette palette = context.palette;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: palette.surfaceSunken,
        borderRadius: const BorderRadius.all(OpenCrayRadii.pill),
      ),
      child: Padding(
        padding: const EdgeInsets.all(4),
        child: Stack(
          children: [
            Positioned.fill(
              child: AnimatedAlign(
                duration: duration,
                curve: OpenCrayMotion.enter,
                alignment: Alignment(thumbX, 0),
                child: FractionallySizedBox(
                  widthFactor: 1 / count,
                  heightFactor: 1,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: palette.surface,
                      borderRadius: const BorderRadius.all(OpenCrayRadii.pill),
                      // Dark has no card shadow to lift the indicator off the
                      // track, and the two fills are only a few steps apart, so
                      // the edge has to come from a hairline instead.
                      border: palette.isDark
                          ? Border.all(color: palette.outline)
                          : null,
                      boxShadow: palette.cardShadow,
                    ),
                  ),
                ),
              ),
            ),
            _buildLabelRow(context, activeIndex, duration, base),
          ],
        ),
      ),
    );
  }

  Widget _buildLabelRow(
    BuildContext context,
    int activeIndex,
    Duration duration,
    TextStyle base,
  ) {
    final OpenCrayPalette palette = context.palette;
    return Row(
      children: [
        for (int index = 0; index < labels.length; index++)
          Expanded(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: onSelected == null
                  ? null
                  : () {
                      if (index != activeIndex) {
                        HapticFeedback.selectionClick();
                      }
                      onSelected!(index);
                    },
              child: Padding(
                padding: EdgeInsets.symmetric(vertical: verticalPadding),
                child: AnimatedDefaultTextStyle(
                  duration: duration,
                  curve: OpenCrayMotion.enter,
                  textAlign: TextAlign.center,
                  style: base.copyWith(
                    color: index == activeIndex
                        ? palette.textPrimary
                        : palette.textSecondary,
                    fontWeight: index == activeIndex
                        ? FontWeight.w600
                        : FontWeight.w500,
                  ),
                  child: Text(
                    labels[index],
                    textAlign: TextAlign.center,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

/// Selection mark for pick-one rows: the ring fills and the tick pops in
/// instead of appearing from nothing.
class OpenCraySelectionCheck extends StatelessWidget {
  const OpenCraySelectionCheck({
    super.key,
    required this.selected,
    this.dimension = 20,
  });

  final bool selected;
  final double dimension;

  @override
  Widget build(BuildContext context) {
    final OpenCrayPalette palette = context.palette;
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.micro,
    );
    return AnimatedContainer(
      duration: duration,
      curve: OpenCrayMotion.enter,
      width: dimension,
      height: dimension,
      decoration: BoxDecoration(
        color: selected ? palette.primary : Colors.transparent,
        shape: BoxShape.circle,
        border: Border.all(
          color: selected ? palette.primary : palette.outline,
          width: selected ? 1 : 1.4,
        ),
      ),
      alignment: Alignment.center,
      child: AnimatedScale(
        duration: duration,
        curve: OpenCrayMotion.enter,
        scale: selected ? 1 : 0.4,
        child: AnimatedOpacity(
          duration: duration,
          curve: OpenCrayMotion.enter,
          opacity: selected ? 1 : 0,
          child: Icon(
            Icons.check_rounded,
            size: dimension - 6,
            color: palette.textOnPrimary,
          ),
        ),
      ),
    );
  }
}

