/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.PaneInfo;
import com.google.cloud.dataflow.sdk.util.TimerInternals.TimerData;
import com.google.cloud.dataflow.sdk.util.state.MergingStateAccessor;
import com.google.cloud.dataflow.sdk.util.state.State;
import com.google.cloud.dataflow.sdk.util.state.StateAccessor;
import com.google.cloud.dataflow.sdk.util.state.StateContext;
import com.google.cloud.dataflow.sdk.util.state.StateContexts;
import com.google.cloud.dataflow.sdk.util.state.StateInternals;
import com.google.cloud.dataflow.sdk.util.state.StateNamespace;
import com.google.cloud.dataflow.sdk.util.state.StateNamespaces;
import com.google.cloud.dataflow.sdk.util.state.StateNamespaces.WindowNamespace;
import com.google.cloud.dataflow.sdk.util.state.StateTag;
import com.google.common.collect.ImmutableMap;

import org.joda.time.Instant;

import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Factory for creating instances of the various {@link ReduceFn} contexts.
 */
class ReduceFnContextFactory<K, InputT, OutputT, W extends BoundedWindow> {
  public interface OnTriggerCallbacks<OutputT> {
    void output(OutputT toOutput);
  }

  private final K key;
  private final ReduceFn<K, InputT, OutputT, W> reduceFn;
  private final WindowingStrategy<?, W> windowingStrategy;
  private final StateInternals<K> stateInternals;
  private final ActiveWindowSet<W> activeWindows;
  private final TimerInternals timerInternals;
  private final WindowingInternals<?, ?> windowingInternals;
  private final PipelineOptions options;

  ReduceFnContextFactory(K key, ReduceFn<K, InputT, OutputT, W> reduceFn,
      WindowingStrategy<?, W> windowingStrategy, StateInternals<K> stateInternals,
      ActiveWindowSet<W> activeWindows, TimerInternals timerInternals,
      WindowingInternals<?, ?> windowingInternals, PipelineOptions options) {
    this.key = key;
    this.reduceFn = reduceFn;
    this.windowingStrategy = windowingStrategy;
    this.stateInternals = stateInternals;
    this.activeWindows = activeWindows;
    this.timerInternals = timerInternals;
    this.windowingInternals = windowingInternals;
    this.options = options;
  }

  /** Where should we look for state associated with a given window? */
  public enum StateStyle {
    /** All state is associated with the window itself. */
    DIRECT,
    /** State is associated with the 'state address' windows tracked by the active window set. */
    RENAMED
  }

  private StateAccessorImpl<K, W> stateAccessor(W window, StateStyle style) {
    return new StateAccessorImpl<K, W>(
        activeWindows, windowingStrategy.getWindowFn().windowCoder(),
        stateInternals, StateContexts.createFromComponents(options, windowingInternals, window),
        style);
  }

  public ReduceFn<K, InputT, OutputT, W>.Context base(W window, StateStyle style) {
    return new ContextImpl(stateAccessor(window, style));
  }

  public ReduceFn<K, InputT, OutputT, W>.ProcessValueContext forValue(
      W window, InputT value, Instant timestamp, StateStyle style) {
    return new ProcessValueContextImpl(stateAccessor(window, style), value, timestamp);
  }

  public ReduceFn<K, InputT, OutputT, W>.OnTriggerContext forTrigger(W window,
      PaneInfo pane, StateStyle style, OnTriggerCallbacks<OutputT> callbacks) {
    return new OnTriggerContextImpl(stateAccessor(window, style), pane, callbacks);
  }

  public ReduceFn<K, InputT, OutputT, W>.OnMergeContext forMerge(
      Collection<W> activeToBeMerged, W mergeResult, StateStyle style) {
    return new OnMergeContextImpl(
        new MergingStateAccessorImpl<K, W>(activeWindows,
            windowingStrategy.getWindowFn().windowCoder(),
            stateInternals, style, activeToBeMerged, mergeResult));
  }

  public ReduceFn<K, InputT, OutputT, W>.OnMergeContext forPremerge(W window) {
    return new OnPremergeContextImpl(new PremergingStateAccessorImpl<K, W>(
        activeWindows, windowingStrategy.getWindowFn().windowCoder(), stateInternals, window));
  }

  private class TimersImpl implements Timers {
    private final StateNamespace namespace;

    public TimersImpl(StateNamespace namespace) {
      checkArgument(namespace instanceof WindowNamespace);
      this.namespace = namespace;
    }

    @Override
    public void setTimer(Instant timestamp, TimeDomain timeDomain) {
      timerInternals.setTimer(TimerData.of(namespace, timestamp, timeDomain));
    }

    @Override
    public void deleteTimer(Instant timestamp, TimeDomain timeDomain) {
      timerInternals.deleteTimer(TimerData.of(namespace, timestamp, timeDomain));
    }

    @Override
    public Instant currentProcessingTime() {
      return timerInternals.currentProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentSynchronizedProcessingTime() {
      return timerInternals.currentSynchronizedProcessingTime();
    }

    @Override
    public Instant currentEventTime() {
      return timerInternals.currentInputWatermarkTime();
    }
  }

  // ======================================================================
  // StateAccessors
  // ======================================================================
  static class StateAccessorImpl<K, W extends BoundedWindow> implements StateAccessor<K> {


    protected final ActiveWindowSet<W> activeWindows;
    protected final StateContext<W> context;
    protected final StateNamespace windowNamespace;
    protected final Coder<W> windowCoder;
    protected final StateInternals<K> stateInternals;
    protected final StateStyle style;

    public StateAccessorImpl(ActiveWindowSet<W> activeWindows, Coder<W> windowCoder,
        StateInternals<K> stateInternals, StateContext<W> context, StateStyle style) {

      this.activeWindows = activeWindows;
      this.windowCoder = windowCoder;
      this.stateInternals = stateInternals;
      this.context = checkNotNull(context);
      this.windowNamespace = namespaceFor(context.window());
      this.style = style;
    }

    protected StateNamespace namespaceFor(W window) {
      return StateNamespaces.window(windowCoder, window);
    }

    protected StateNamespace windowNamespace() {
      return windowNamespace;
    }

    W window() {
      return context.window();
    }

    StateNamespace namespace() {
      return windowNamespace();
    }

    @Override
    public <StateT extends State> StateT access(StateTag<? super K, StateT> address) {
      switch (style) {
        case DIRECT:
          return stateInternals.state(windowNamespace(), address, context);
        case RENAMED:
          return stateInternals.state(
              namespaceFor(activeWindows.writeStateAddress(context.window())), address, context);
      }
      throw new RuntimeException(); // cases are exhaustive.
    }
  }

  static class MergingStateAccessorImpl<K, W extends BoundedWindow>
      extends StateAccessorImpl<K, W> implements MergingStateAccessor<K, W> {
    private final Collection<W> activeToBeMerged;

    public MergingStateAccessorImpl(ActiveWindowSet<W> activeWindows, Coder<W> windowCoder,
        StateInternals<K> stateInternals, StateStyle style, Collection<W> activeToBeMerged,
        W mergeResult) {
      super(activeWindows, windowCoder, stateInternals,
          StateContexts.windowOnly(mergeResult), style);
      this.activeToBeMerged = activeToBeMerged;
    }

    @Override
    public <StateT extends State> StateT access(StateTag<? super K, StateT> address) {
      switch (style) {
        case DIRECT:
          return stateInternals.state(windowNamespace(), address, context);
        case RENAMED:
          return stateInternals.state(
              namespaceFor(activeWindows.mergedWriteStateAddress(
                  activeToBeMerged, context.window())),
              address,
              context);
      }
      throw new RuntimeException(); // cases are exhaustive.
    }

    @Override
    public <StateT extends State> Map<W, StateT> accessInEachMergingWindow(
        StateTag<? super K, StateT> address) {
      ImmutableMap.Builder<W, StateT> builder = ImmutableMap.builder();
      for (W mergingWindow : activeToBeMerged) {
        StateNamespace namespace = null;
        switch (style) {
          case DIRECT:
            namespace = namespaceFor(mergingWindow);
            break;
          case RENAMED:
            namespace = namespaceFor(activeWindows.writeStateAddress(mergingWindow));
            break;
        }
        checkNotNull(namespace); // cases are exhaustive.
        builder.put(mergingWindow, stateInternals.state(namespace, address, context));
      }
      return builder.build();
    }
  }

  static class PremergingStateAccessorImpl<K, W extends BoundedWindow>
      extends StateAccessorImpl<K, W> implements MergingStateAccessor<K, W> {
    public PremergingStateAccessorImpl(ActiveWindowSet<W> activeWindows, Coder<W> windowCoder,
        StateInternals<K> stateInternals, W window) {
      super(activeWindows, windowCoder, stateInternals,
          StateContexts.windowOnly(window), StateStyle.RENAMED);
    }

    Collection<W> mergingWindows() {
      return activeWindows.readStateAddresses(context.window());
    }

    @Override
    public <StateT extends State> Map<W, StateT> accessInEachMergingWindow(
        StateTag<? super K, StateT> address) {
      ImmutableMap.Builder<W, StateT> builder = ImmutableMap.builder();
      for (W stateAddressWindow : activeWindows.readStateAddresses(context.window())) {
        StateT stateForWindow =
            stateInternals.state(namespaceFor(stateAddressWindow), address, context);
        builder.put(stateAddressWindow, stateForWindow);
      }
      return builder.build();
    }
  }

  // ======================================================================
  // Contexts
  // ======================================================================

  private class ContextImpl extends ReduceFn<K, InputT, OutputT, W>.Context {
    private final StateAccessorImpl<K, W> state;
    private final TimersImpl timers;

    private ContextImpl(StateAccessorImpl<K, W> state) {
      reduceFn.super();
      this.state = state;
      this.timers = new TimersImpl(state.namespace());
    }

    @Override
    public K key() {
      return key;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public WindowingStrategy<?, W> windowingStrategy() {
      return windowingStrategy;
    }

    @Override
    public StateAccessor<K> state() {
      return state;
    }

    @Override
    public Timers timers() {
      return timers;
    }
  }

  private class ProcessValueContextImpl
      extends ReduceFn<K, InputT, OutputT, W>.ProcessValueContext {
    private final InputT value;
    private final Instant timestamp;
    private final StateAccessorImpl<K, W> state;
    private final TimersImpl timers;

    private ProcessValueContextImpl(StateAccessorImpl<K, W> state,
        InputT value, Instant timestamp) {
      reduceFn.super();
      this.state = state;
      this.value = value;
      this.timestamp = timestamp;
      this.timers = new TimersImpl(state.namespace());
    }

    @Override
    public K key() {
      return key;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public WindowingStrategy<?, W> windowingStrategy() {
      return windowingStrategy;
    }

    @Override
    public StateAccessor<K> state() {
      return state;
    }

    @Override
    public InputT value() {
      return value;
    }

    @Override
    public Instant timestamp() {
      return timestamp;
    }

    @Override
    public Timers timers() {
      return timers;
    }
  }

  private class OnTriggerContextImpl extends ReduceFn<K, InputT, OutputT, W>.OnTriggerContext {
    private final StateAccessorImpl<K, W> state;
    private final PaneInfo pane;
    private final OnTriggerCallbacks<OutputT> callbacks;
    private final TimersImpl timers;

    private OnTriggerContextImpl(StateAccessorImpl<K, W> state, PaneInfo pane,
        OnTriggerCallbacks<OutputT> callbacks) {
      reduceFn.super();
      this.state = state;
      this.pane = pane;
      this.callbacks = callbacks;
      this.timers = new TimersImpl(state.namespace());
    }

    @Override
    public K key() {
      return key;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public WindowingStrategy<?, W> windowingStrategy() {
      return windowingStrategy;
    }

    @Override
    public StateAccessor<K> state() {
      return state;
    }

    @Override
    public PaneInfo paneInfo() {
      return pane;
    }

    @Override
    public void output(OutputT value) {
      callbacks.output(value);
    }

    @Override
    public Timers timers() {
      return timers;
    }
  }

  private class OnMergeContextImpl extends ReduceFn<K, InputT, OutputT, W>.OnMergeContext {
    private final MergingStateAccessorImpl<K, W> state;
    private final TimersImpl timers;

    private OnMergeContextImpl(MergingStateAccessorImpl<K, W> state) {
      reduceFn.super();
      this.state = state;
      this.timers = new TimersImpl(state.namespace());
    }

    @Override
    public K key() {
      return key;
    }

    @Override
    public WindowingStrategy<?, W> windowingStrategy() {
      return windowingStrategy;
    }

    @Override
    public MergingStateAccessor<K, W> state() {
      return state;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public Timers timers() {
      return timers;
    }
  }

  private class OnPremergeContextImpl extends ReduceFn<K, InputT, OutputT, W>.OnMergeContext {
    private final PremergingStateAccessorImpl<K, W> state;
    private final TimersImpl timers;

    private OnPremergeContextImpl(PremergingStateAccessorImpl<K, W> state) {
      reduceFn.super();
      this.state = state;
      this.timers = new TimersImpl(state.namespace());
    }

    @Override
    public K key() {
      return key;
    }

    @Override
    public WindowingStrategy<?, W> windowingStrategy() {
      return windowingStrategy;
    }

    @Override
    public MergingStateAccessor<K, W> state() {
      return state;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public Timers timers() {
      return timers;
    }
  }
}
