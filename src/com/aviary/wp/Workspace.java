/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aviary.wp;

import java.util.ArrayList;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Interpolator;
import android.widget.Adapter;
import android.widget.LinearLayout;
import android.widget.Scroller;

public class Workspace extends ViewGroup {

	public static final String TAG = "Launcher.Workspace";
	private static final int INVALID_SCREEN = -1;
	public static final int OVER_SCROLL_NEVER = 0;
	public static final int OVER_SCROLL_ALWAYS = 1;
	public static final int OVER_SCROLL_IF_CONTENT_SCROLLS = 2;

	/**
	 * The velocity at which a fling gesture will cause us to snap to the next screen
	 */
	private static final int SNAP_VELOCITY = 600;

	private int mDefaultScreen;
	private boolean mFirstLayout = true;

	private int mCurrentScreen;
	private int mNextScreen = INVALID_SCREEN;
	private int mOldSelectedPosition = INVALID_SCREEN;

	private Scroller mScroller;
	private VelocityTracker mVelocityTracker;

	private float mLastMotionX;
	private float mLastMotionY;

	private final static int TOUCH_STATE_REST = 0;
	private final static int TOUCH_STATE_SCROLLING = 1;

	private int mTouchState = TOUCH_STATE_REST;

	private boolean mAllowLongPress = true;

	private int mTouchSlop;
	private int mMaximumVelocity;

	private static final int INVALID_POINTER = -1;

	private int mActivePointerId = INVALID_POINTER;

	private WorkspaceIndicator mIndicator;

	private static final float NANOTIME_DIV = 1000000000.0f;
	private static final float SMOOTHING_SPEED = 0.75f;
	private static final float SMOOTHING_CONSTANT = (float) ( 0.016 / Math.log( SMOOTHING_SPEED ) );
	private static final float BASELINE_FLING_VELOCITY = 1500.f;
	private static final float FLING_VELOCITY_INFLUENCE = .1f;

	private float mSmoothingTime;
	private float mTouchX;

	private WorkspaceOvershootInterpolator mScrollInterpolator;

	protected Adapter mAdapter;
	protected DataSetObserver mObserver;
	protected boolean mDataChanged;
	protected int mFirstPosition;
	protected int mItemCount = 0;
	protected final RecycleBin mRecycler = new RecycleBin( 10 );
	private int mHeightMeasureSpec;
	private int mWidthMeasureSpec;
	private EdgeGlow mEdgeGlowLeft;
	private EdgeGlow mEdgeGlowRight;
	private int mOverScrollMode;

	private static class WorkspaceOvershootInterpolator implements Interpolator {

		private static final float DEFAULT_TENSION = 1.3f;
		private float mTension;

		public WorkspaceOvershootInterpolator() {
			mTension = DEFAULT_TENSION;
		}

		public void setDistance( int distance ) {
			mTension = distance > 0 ? DEFAULT_TENSION / distance : DEFAULT_TENSION;
		}

		public void disableSettle() {
			mTension = 0.f;
		}

		@Override
		public float getInterpolation( float t ) {
			t -= 1.0f;
			return t * t * ( ( mTension + 1 ) * t + mTension ) + 1.0f;
		}
	}

	public Workspace( Context context, AttributeSet attrs ) {
		this( context, attrs, 0 );
		initWorkspace( context, attrs, 0 );
	}

	public Workspace( Context context, AttributeSet attrs, int defStyle ) {
		super( context, attrs, defStyle );
		initWorkspace( context, attrs, defStyle );
	}

	private void initWorkspace( Context context, AttributeSet attrs, int defStyle ) {
		TypedArray a = context.obtainStyledAttributes( attrs, R.styleable.Workspace, defStyle, 0 );
		mDefaultScreen = a.getInt( R.styleable.Workspace_defaultScreen, 0 );
		a.recycle();

		setHapticFeedbackEnabled( false );

		mScrollInterpolator = new WorkspaceOvershootInterpolator();
		mScroller = new Scroller( context, mScrollInterpolator );
		mCurrentScreen = mDefaultScreen;
		Launcher.setScreen( mCurrentScreen );

		final ViewConfiguration configuration = ViewConfiguration.get( getContext() );
		mTouchSlop = configuration.getScaledTouchSlop();
		mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();

		int overscrollMode = a.getInt( R.styleable.Workspace_overscroll, 0 );
		setOverScroll( overscrollMode );
	}

	public void setOverScroll( int mode ) {
		if ( mode != OVER_SCROLL_NEVER ) {
			if ( mEdgeGlowLeft == null ) {
				final Resources res = getContext().getResources();
				final Drawable edge = res.getDrawable( R.drawable.overscroll_edge );
				final Drawable glow = res.getDrawable( R.drawable.overscroll_glow );
				mEdgeGlowLeft = new EdgeGlow( null, glow );
				mEdgeGlowRight = new EdgeGlow( null, glow );
			}
		} else {
			mEdgeGlowLeft = null;
			mEdgeGlowRight = null;
		}
		mOverScrollMode = mode;
	}

	public int getOverScroll() {
		return mOverScrollMode;
	}

	public void setAdapter( Adapter adapter ) {
		
		if( mAdapter != null ){
			mAdapter.unregisterDataSetObserver( mObserver );
			mAdapter = null;
		}
		
		mAdapter = adapter;
		resetList();

		mObserver = new WorkspaceDataSetObserver();
		mAdapter.registerDataSetObserver( mObserver );

		mDataChanged = true;
		mItemCount = adapter.getCount();
		requestLayout();
	}

	@Override
	public void addView( View child, int index, LayoutParams params ) {
		if ( !( child instanceof CellLayout ) ) {
			throw new IllegalArgumentException( "A Workspace can only have CellLayout children." );
		}
		super.addView( child, index, params );
	}

	@Override
	public void addView( View child ) {
		if ( !( child instanceof CellLayout ) ) {
			throw new IllegalArgumentException( "A Workspace can only have CellLayout children." );
		}
		super.addView( child );
	}

	@Override
	public void addView( View child, int index ) {
		if ( !( child instanceof CellLayout ) ) {
			throw new IllegalArgumentException( "A Workspace can only have CellLayout children." );
		}
		super.addView( child, index );
	}

	@Override
	public void addView( View child, int width, int height ) {
		if ( !( child instanceof CellLayout ) ) {
			throw new IllegalArgumentException( "A Workspace can only have CellLayout children." );
		}
		super.addView( child, width, height );
	}

	@Override
	public void addView( View child, LayoutParams params ) {
		if ( !( child instanceof CellLayout ) ) {
			throw new IllegalArgumentException( "A Workspace can only have CellLayout children." );
		}
		super.addView( child, params );
	}

	boolean isDefaultScreenShowing() {
		return mCurrentScreen == mDefaultScreen;
	}

	/**
	 * Returns the index of the currently displayed screen.
	 * 
	 * @return The index of the currently displayed screen.
	 */
	int getCurrentScreen() {
		return mCurrentScreen;
	}

	/**
	 * Sets the current screen.
	 * 
	 * @param currentScreen
	 */
	void setCurrentScreen( int currentScreen ) {
		if ( !mScroller.isFinished() ) mScroller.abortAnimation();
		mCurrentScreen = Math.max( 0, Math.min( currentScreen, mItemCount - 1 ) );
		mIndicator.setLevel( mCurrentScreen, mItemCount );
		scrollTo( mCurrentScreen * getWidth(), 0 );
		invalidate();
	}

	@Override
	public void scrollTo( int x, int y ) {
		super.scrollTo( x, y );
		mTouchX = x;
		mSmoothingTime = System.nanoTime() / NANOTIME_DIV;
	}

	@Override
	public void computeScroll() {

		if ( mScroller.computeScrollOffset() ) {
			mTouchX = mScroller.getCurrX();
			float mScrollX = mTouchX;
			mSmoothingTime = System.nanoTime() / NANOTIME_DIV;
			float mScrollY = mScroller.getCurrY();
			scrollTo( (int) mScrollX, (int) mScrollY );
			postInvalidate();
		} else if ( mNextScreen != INVALID_SCREEN ) {
			int which = Math.max( 0, Math.min( mNextScreen, mItemCount - 1 ) );
			onFinishedAnimation( which );
		} else if ( mTouchState == TOUCH_STATE_SCROLLING ) {
			final float now = System.nanoTime() / NANOTIME_DIV;
			final float e = (float) Math.exp( ( now - mSmoothingTime ) / SMOOTHING_CONSTANT );
			final float dx = mTouchX - getScrollX();
			float mScrollX = getScrollX() + ( dx * e );
			scrollTo( (int) mScrollX, 0 );
			mSmoothingTime = now;

			// Keep generating points as long as we're more than 1px away from the target
			if ( dx > 1.f || dx < -1.f ) {
				postInvalidate();
			}
		}
	}

	private void onFinishedAnimation( int newScreen ) {

		final boolean toLeft = newScreen > mCurrentScreen;
		final boolean toRight = newScreen < mCurrentScreen;
		final boolean changed = newScreen != mCurrentScreen;

		mCurrentScreen = newScreen;
		mIndicator.setLevel( mCurrentScreen, mItemCount );
		Launcher.setScreen( mCurrentScreen );
		mNextScreen = INVALID_SCREEN;

		fillToGalleryRight();
		fillToGalleryLeft();

		if ( toLeft ) {
			detachOffScreenChildren( true );
		} else if ( toRight ) {
			detachOffScreenChildren( false );
		}

		if ( changed ) {
			/*
			 * setSelectedPositionInt( newScreen ); setNextSelectedPositionInt( newScreen ); checkSelectionChanged();
			 * 
			 * if( mPageChangeListener != null ) mPageChangeListener.onPageChanged( newScreen, mItemCount );
			 */
		}

		clearChildrenCache();
	}

	private void detachOffScreenChildren( boolean toLeft ) {
		int numChildren = getChildCount();
		int start = 0;
		int count = 0;

		if ( toLeft ) {
			final int galleryLeft = getPaddingLeft() + getScreenScrollPositionX( mCurrentScreen - 1 );;
			for ( int i = 0; i < numChildren; i++ ) {
				final View child = getChildAt( i );
				if ( child.getRight() >= galleryLeft ) {
					break;
				} else {
					count++;
					mRecycler.add( child );
				}
			}
		} else {
			final int galleryRight = getTotalWidth() + getScreenScrollPositionX( mCurrentScreen + 1 );
			for ( int i = numChildren - 1; i >= 0; i-- ) {
				final View child = getChildAt( i );
				if ( child.getLeft() <= galleryRight ) {
					break;
				} else {
					start = i;
					count++;
					mRecycler.add( child );
				}
			}
		}

		detachViewsFromParent( start, count );
		if ( toLeft && count > 0 ) {
			mFirstPosition += count;
		}
	}

	private void drawEdges( Canvas canvas ) {

		if ( mEdgeGlowLeft != null ) {
			if ( !mEdgeGlowLeft.isFinished() ) {
				final int restoreCount = canvas.save();
				final int height = getHeight();

				canvas.rotate( -90 );
				canvas.translate( -height / 1.5f, 0 );
				mEdgeGlowLeft.setSize( (int) ( height / 2.5f ), height / 5 );

				if ( mEdgeGlowLeft.draw( canvas ) ) {
					invalidate();
				}
				canvas.restoreToCount( restoreCount );
			}
			if ( !mEdgeGlowRight.isFinished() ) {
				final int restoreCount = canvas.save();
				final int width = getWidth();
				final int height = getHeight();

				canvas.translate( getScrollX() + width, height / 3f );
				canvas.rotate( 90 );
				mEdgeGlowRight.setSize( (int) ( height / 2.5f ), height / 5 );
				if ( mEdgeGlowRight.draw( canvas ) ) {
					invalidate();
				}
				canvas.restoreToCount( restoreCount );
			}
		}
	}

	@Override
	protected void dispatchDraw( Canvas canvas ) {
		boolean restore = false;
		int restoreCount = 0;

		if ( mItemCount < 1 ) return;

		boolean fastDraw = mTouchState != TOUCH_STATE_SCROLLING && mNextScreen == INVALID_SCREEN;
		// If we are not scrolling or flinging, draw only the current screen
		if ( fastDraw ) {
			drawChild( canvas, getChildAt( mCurrentScreen - mFirstPosition ), getDrawingTime() );
		} else {
			final long drawingTime = getDrawingTime();
			final float scrollPos = (float) getScrollX() / getTotalWidth();
			final int leftScreen = (int) scrollPos;
			final int rightScreen = leftScreen + 1;
			if ( leftScreen >= 0 ) {
				drawChild( canvas, getChildAt( leftScreen - mFirstPosition ), drawingTime );
			}
			if ( scrollPos != leftScreen && rightScreen < mItemCount ) {
				drawChild( canvas, getChildAt( rightScreen - mFirstPosition ), drawingTime );
			}
		}

		if ( mEdgeGlowLeft != null ) {
			drawEdges( canvas );
		}

		if ( restore ) {
			canvas.restoreToCount( restoreCount );
		}
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		computeScroll();
	}

	@Override
	protected void onMeasure( int widthMeasureSpec, int heightMeasureSpec ) {
		super.onMeasure( widthMeasureSpec, heightMeasureSpec );
		
		Log.d( TAG, "onMeasure" );

		mWidthMeasureSpec = widthMeasureSpec;
		mHeightMeasureSpec = heightMeasureSpec;

		if ( mDataChanged ) {
			mFirstLayout = true;
			resetList();
			handleDataChanged();
		}

		boolean needsMeasuring = true;

		if ( mNextScreen > INVALID_SCREEN && mAdapter != null && mNextScreen < mItemCount ) {

		}

		final int width = MeasureSpec.getSize( widthMeasureSpec );
		final int height = MeasureSpec.getSize( heightMeasureSpec );
		final int widthMode = MeasureSpec.getMode( widthMeasureSpec );

		if ( widthMode != MeasureSpec.EXACTLY ) {
			throw new IllegalStateException( "Workspace can only be used in EXACTLY mode." );
		}

		final int heightMode = MeasureSpec.getMode( heightMeasureSpec );

		if ( heightMode != MeasureSpec.EXACTLY ) {
			throw new IllegalStateException( "Workspace can only be used in EXACTLY mode." );
		}

		// The children are given the same width and height as the workspace
		final int count = mItemCount;

		if ( !needsMeasuring ) {
			for ( int i = 0; i < count; i++ ) {
				getChildAt( i ).measure( widthMeasureSpec, heightMeasureSpec );
			}
		}

		if ( mItemCount < 1 ) {
			mCurrentScreen = INVALID_SCREEN;
			mFirstLayout = true;
		}

		if ( mFirstLayout ) {
			setHorizontalScrollBarEnabled( false );

			if ( mCurrentScreen > INVALID_SCREEN )
				scrollTo( mCurrentScreen * width, 0 );
			else
				scrollTo( 0, 0 );
			setHorizontalScrollBarEnabled( true );
			mFirstLayout = false;
		}

	}

	private void handleDataChanged() {
		if ( mItemCount > 0 )
			setNextSelectedPositionInt( 0 );
		else
			setNextSelectedPositionInt( -1 );
	}

	@Override
	protected void onLayout( boolean changed, int left, int top, int right, int bottom ) {
		Log.d( TAG, "onLayout: " + changed + ", " + left + ", " + top + ", " + right + ", " + bottom );
		
		if( changed ){
			if( !mFirstLayout )
			{
				mDataChanged = true;
				measure( mWidthMeasureSpec, mHeightMeasureSpec );
			}
		}
		
		layout( 0, false );
	}

	void layout( int delta, boolean animate ) {

		int childrenLeft = getPaddingLeft();
		int childrenWidth = getRight() - getLeft() - getPaddingLeft() - getPaddingRight();

		if ( mItemCount == 0 ) {
			return;
		}

		if ( mNextScreen > INVALID_SCREEN ) {
			setSelectedPositionInt( mNextScreen );
		}

		if ( mDataChanged ) {
			mFirstPosition = mCurrentScreen;
			View sel = makeAndAddView( mCurrentScreen, 0, 0, true );
			int selectedOffset = childrenLeft + ( childrenWidth / 2 ) - ( sel.getWidth() / 2 );
			sel.offsetLeftAndRight( selectedOffset );
			fillToGalleryRight();
			fillToGalleryLeft();
			checkSelectionChanged();
		}

		mDataChanged = false;
		setNextSelectedPositionInt( mCurrentScreen );
	}

	void checkSelectionChanged() {
		if ( ( mCurrentScreen != mOldSelectedPosition ) ) {
			// selectionChanged();
			mOldSelectedPosition = mCurrentScreen;
		}
	}

	private View makeAndAddView( int position, int offset, int x, boolean fromLeft ) {

		View child;

		if ( !mDataChanged ) {
			child = mRecycler.remove();
			if ( child != null ) {
				child = mAdapter.getView( position, child, this );
				setUpChild( child, offset, x, fromLeft );
				return child;
			}
		}

		// Nothing found in the recycler -- ask the adapter for a view
		child = mAdapter.getView( position, null, this );

		// Position the view
		setUpChild( child, offset, x, fromLeft );

		return child;
	}

	private void setUpChild( View child, int offset, int x, boolean fromLeft ) {

		// Respect layout params that are already in the view. Otherwise
		// make some up...
		LayoutParams lp = child.getLayoutParams();
		if ( lp == null ) {
			lp = (LayoutParams) generateDefaultLayoutParams();
		}

		addViewInLayout( child, fromLeft ? -1 : 0, lp );
		child.setSelected( offset == 0 );

		// Get measure specs
		int childHeightSpec = ViewGroup.getChildMeasureSpec( mHeightMeasureSpec, getPaddingTop() + getPaddingBottom(), lp.height );
		int childWidthSpec = ViewGroup.getChildMeasureSpec( mWidthMeasureSpec, getPaddingLeft() + getPaddingRight(), lp.width );

		// Measure child
		child.measure( childWidthSpec, childHeightSpec );

		int childLeft;
		int childRight;

		// Position vertically based on gravity setting
		int childTop = calculateTop( child, true );
		int childBottom = childTop + child.getMeasuredHeight();

		int width = child.getMeasuredWidth();
		if ( fromLeft ) {
			childLeft = x;
			childRight = childLeft + width;
		} else {
			childLeft = x - width;
			childRight = x;
		}

		child.layout( childLeft, childTop, childRight, childBottom );
	}

	private int calculateTop( View child, boolean duringLayout ) {
		return getPaddingTop();
	}

	private int getTotalWidth() {
		return getRight() - getLeft() - getPaddingRight();
	}

	private int getScreenScrollPositionX( int screen ) {
		return ( screen * getTotalWidth() );
	}

	private void fillToGalleryRight() {
		int itemSpacing = 0;
		int galleryRight = getScreenScrollPositionX( mCurrentScreen + 3 );
		int numChildren = getChildCount();
		int numItems = mItemCount;

		// Set state for initial iteration
		View prevIterationView = getChildAt( numChildren - 1 );
		int curPosition;
		int curLeftEdge;

		if ( prevIterationView != null ) {
			curPosition = mFirstPosition + numChildren;
			curLeftEdge = prevIterationView.getRight() + itemSpacing;
		} else {
			mFirstPosition = curPosition = mItemCount - 1;
			curLeftEdge = getPaddingLeft();
		}

		while ( curLeftEdge < galleryRight && curPosition < numItems ) {
			prevIterationView = makeAndAddView( curPosition, curPosition - mCurrentScreen, curLeftEdge, true );

			// Set state for next iteration
			curLeftEdge = prevIterationView.getRight() + itemSpacing;
			curPosition++;
		}
	}

	private void fillToGalleryLeft() {
		int itemSpacing = 0;
		int galleryLeft = getScreenScrollPositionX( mCurrentScreen - 3 );

		// Set state for initial iteration
		View prevIterationView = getChildAt( 0 );
		int curPosition;
		int curRightEdge;

		if ( prevIterationView != null ) {
			curPosition = mFirstPosition - 1;
			curRightEdge = prevIterationView.getLeft() - itemSpacing;
		} else {
			// No children available!
			curPosition = 0;
			curRightEdge = getRight() - getLeft() - getPaddingRight();
		}

		while ( curRightEdge > galleryLeft && curPosition >= 0 ) {
			prevIterationView = makeAndAddView( curPosition, curPosition - mCurrentScreen, curRightEdge, false );

			// Remember some state
			mFirstPosition = curPosition;

			// Set state for next iteration
			curRightEdge = prevIterationView.getLeft() - itemSpacing;
			curPosition--;
		}
	}

	@Override
	protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
		return new LinearLayout.LayoutParams( ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT );
	}

	void recycleAllViews() {
		final int childCount = getChildCount();

		for ( int i = 0; i < childCount; i++ ) {
			View v = getChildAt( i );
			mRecycler.add( v );
		}
	}

	void resetList() {
		recycleAllViews();
		detachAllViewsFromParent();
		mRecycler.clear();

		scrollTo( 0, 0 );
		
		mOldSelectedPosition = INVALID_SCREEN;
		setSelectedPositionInt( INVALID_SCREEN );
		setNextSelectedPositionInt( INVALID_SCREEN );
		postInvalidate();
	}

	private void setNextSelectedPositionInt( int screen ) {
		mNextScreen = screen;
	}

	private void setSelectedPositionInt( int screen ) {
		mCurrentScreen = screen;
	}

	@Override
	public boolean requestChildRectangleOnScreen( View child, Rect rectangle, boolean immediate ) {
		int screen = indexOfChild( child );

		if ( screen != mCurrentScreen || !mScroller.isFinished() ) {
			snapToScreen( screen );
			return true;
		}
		return false;
	}

	@Override
	protected boolean onRequestFocusInDescendants( int direction, Rect previouslyFocusedRect ) {

		if ( mItemCount < 1 ) return false;

		if ( isEnabled() ) {
			int focusableScreen;
			if ( mNextScreen != INVALID_SCREEN ) {
				focusableScreen = mNextScreen;
			} else {
				focusableScreen = mCurrentScreen;
			}

			if ( focusableScreen != INVALID_SCREEN ) {
				getChildAt( focusableScreen ).requestFocus( direction, previouslyFocusedRect );
			}
		}
		return false;
	}

	@Override
	public boolean dispatchUnhandledMove( View focused, int direction ) {

		if ( direction == View.FOCUS_LEFT ) {
			if ( getCurrentScreen() > 0 ) {
				snapToScreen( getCurrentScreen() - 1 );
				return true;
			}
		} else if ( direction == View.FOCUS_RIGHT ) {
			if ( getCurrentScreen() < mItemCount - 1 ) {
				snapToScreen( getCurrentScreen() + 1 );
				return true;
			}
		}
		return super.dispatchUnhandledMove( focused, direction );
	}

	@Override
	public void setEnabled( boolean enabled ) {
		super.setEnabled( enabled );

		for ( int i = 0; i < getChildCount(); i++ ) {
			getChildAt( i ).setEnabled( enabled );
		}

	}

	@Override
	public void addFocusables( ArrayList<View> views, int direction, int focusableMode ) {

		if ( isEnabled() ) {
			getChildAt( mCurrentScreen ).addFocusables( views, direction );
			if ( direction == View.FOCUS_LEFT ) {
				if ( mCurrentScreen > 0 ) {
					getChildAt( mCurrentScreen - 1 ).addFocusables( views, direction );
				}
			} else if ( direction == View.FOCUS_RIGHT ) {
				if ( mCurrentScreen < mItemCount - 1 ) {
					getChildAt( mCurrentScreen + 1 ).addFocusables( views, direction );
				}
			}
		}
	}

	@Override
	public boolean onInterceptTouchEvent( MotionEvent ev ) {

		final int action = ev.getAction();
		if ( !isEnabled() ) {
			return false; // We don't want the events. Let them fall through to the all apps view.
		}

		if ( ( action == MotionEvent.ACTION_MOVE ) && ( mTouchState != TOUCH_STATE_REST ) ) {
			return true;
		}

		acquireVelocityTrackerAndAddMovement( ev );

		switch ( action & MotionEvent.ACTION_MASK ) {
			case MotionEvent.ACTION_MOVE: {

				/*
				 * Locally do absolute value. mLastMotionX is set to the y value of the down event.
				 */
				final int pointerIndex = ev.findPointerIndex( mActivePointerId );
				final float x = ev.getX( pointerIndex );
				final float y = ev.getY( pointerIndex );
				final int xDiff = (int) Math.abs( x - mLastMotionX );
				final int yDiff = (int) Math.abs( y - mLastMotionY );

				final int touchSlop = mTouchSlop;
				boolean xMoved = xDiff > touchSlop;
				boolean yMoved = yDiff > touchSlop;

				if ( xMoved || yMoved ) {

					if ( xMoved ) {
						// Scroll if the user moved far enough along the X axis
						mTouchState = TOUCH_STATE_SCROLLING;
						mLastMotionX = x;
						mTouchX = getScrollX();
						mSmoothingTime = System.nanoTime() / NANOTIME_DIV;
						enableChildrenCache( mCurrentScreen - 1, mCurrentScreen + 1 );
					}

				}
				break;
			}

			case MotionEvent.ACTION_DOWN: {
				final float x = ev.getX();
				final float y = ev.getY();
				// Remember location of down touch
				mLastMotionX = x;
				mLastMotionY = y;
				mActivePointerId = ev.getPointerId( 0 );
				mAllowLongPress = true;

				mTouchState = mScroller.isFinished() ? TOUCH_STATE_REST : TOUCH_STATE_SCROLLING;
				break;
			}

			case MotionEvent.ACTION_CANCEL:
			case MotionEvent.ACTION_UP:
				// Release the drag
				clearChildrenCache();
				mTouchState = TOUCH_STATE_REST;
				mActivePointerId = INVALID_POINTER;
				mAllowLongPress = false;
				releaseVelocityTracker();
				break;

			case MotionEvent.ACTION_POINTER_UP:
				onSecondaryPointerUp( ev );
				break;
		}

		/*
		 * The only time we want to intercept motion events is if we are in the drag mode.
		 */
		return mTouchState != TOUCH_STATE_REST;
	}

	private void onSecondaryPointerUp( MotionEvent ev ) {
		final int pointerIndex = ( ev.getAction() & MotionEvent.ACTION_POINTER_INDEX_MASK ) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
		final int pointerId = ev.getPointerId( pointerIndex );
		if ( pointerId == mActivePointerId ) {
			// This was our active pointer going up. Choose a new
			// active pointer and adjust accordingly.
			// TODO: Make this decision more intelligent.
			final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
			mLastMotionX = ev.getX( newPointerIndex );
			mLastMotionY = ev.getY( newPointerIndex );
			mActivePointerId = ev.getPointerId( newPointerIndex );
			if ( mVelocityTracker != null ) {
				mVelocityTracker.clear();
			}
		}
	}

	/**
	 * If one of our descendant views decides that it could be focused now, only pass that along if it's on the current screen.
	 * 
	 * This happens when live folders requery, and if they're off screen, they end up calling requestFocus, which pulls it on screen.
	 */
	@Override
	public void focusableViewAvailable( View focused ) {
		View current = getChildAt( mCurrentScreen );
		View v = focused;
		while ( true ) {
			if ( v == current ) {
				super.focusableViewAvailable( focused );
				return;
			}
			if ( v == this ) {
				return;
			}
			ViewParent parent = v.getParent();
			if ( parent instanceof View ) {
				v = (View) v.getParent();
			} else {
				return;
			}
		}
	}

	void enableChildrenCache( int fromScreen, int toScreen ) {
		if ( fromScreen > toScreen ) {
			final int temp = fromScreen;
			fromScreen = toScreen;
			toScreen = temp;
		}

		final int count = getChildCount();

		fromScreen = Math.max( fromScreen, 0 );
		toScreen = Math.min( toScreen, count - 1 );

		for ( int i = fromScreen; i <= toScreen; i++ ) {
			final CellLayout layout = (CellLayout) getChildAt( i );
			layout.setChildrenDrawnWithCacheEnabled( true );
			layout.setChildrenDrawingCacheEnabled( true );
		}
	}

	void clearChildrenCache() {
		final int count = getChildCount();
		for ( int i = 0; i < count; i++ ) {
			final CellLayout layout = (CellLayout) getChildAt( i );
			layout.setChildrenDrawnWithCacheEnabled( false );
		}
	}

	@Override
	public boolean onTouchEvent( MotionEvent ev ) {

		final int action = ev.getAction();

		if ( !isEnabled() ) {
			if ( !mScroller.isFinished() ) {
				mScroller.abortAnimation();
			}
			snapToScreen( mCurrentScreen );
			return false; // We don't want the events. Let them fall through to the all apps view.
		}

		acquireVelocityTrackerAndAddMovement( ev );

		switch ( action & MotionEvent.ACTION_MASK ) {
			case MotionEvent.ACTION_DOWN:

				if ( !mScroller.isFinished() ) {
					mScroller.abortAnimation();
				}

				// Remember where the motion event started
				mLastMotionX = ev.getX();
				mActivePointerId = ev.getPointerId( 0 );
				if ( mTouchState == TOUCH_STATE_SCROLLING ) {
					enableChildrenCache( mCurrentScreen - 1, mCurrentScreen + 1 );
				}
				break;
			case MotionEvent.ACTION_MOVE:
				if ( mTouchState == TOUCH_STATE_SCROLLING ) {
					final int pointerIndex = ev.findPointerIndex( mActivePointerId );
					final float x = ev.getX( pointerIndex );
					final float deltaX = mLastMotionX - x;
					final int mode = mOverScrollMode;

					//if ( !( Math.abs( deltaX ) > mTouchSlop ) ) {
					//	break;
					//}

					mLastMotionX = x;

					if ( deltaX < 0 ) {
						mTouchX += deltaX;
						mSmoothingTime = System.nanoTime() / NANOTIME_DIV;

						if ( mTouchX < 0 && mode != OVER_SCROLL_NEVER ) {
							mTouchX = 0;
							mLastMotionX = mTouchX;

							if ( mEdgeGlowLeft != null ) {
								float overscroll = ( (float) -deltaX * 2 ) / getWidth();
								mEdgeGlowLeft.onPull( overscroll );
								if ( !mEdgeGlowRight.isFinished() ) {
									mEdgeGlowRight.onRelease();
								}
							}
						}

						invalidate();

					} else if ( deltaX > 0 ) {
						final int totalWidth = getScreenScrollPositionX( mItemCount - 1 );
						final float availableToScroll = getScreenScrollPositionX( mItemCount ) - mTouchX;
						mSmoothingTime = System.nanoTime() / NANOTIME_DIV;
						
						mTouchX += Math.min( availableToScroll, deltaX );
						
						if( availableToScroll <= getWidth() && mode != OVER_SCROLL_NEVER ){
							mTouchX = totalWidth;
							mLastMotionX = totalWidth;
							
							if ( mEdgeGlowLeft != null ) {
								float overscroll = ( (float) deltaX * 2 ) / getWidth();
								mEdgeGlowRight.onPull( overscroll );
								if ( !mEdgeGlowLeft.isFinished() ) {
									mEdgeGlowLeft.onRelease();
								}
							}
						}
						invalidate();

					} else {
						awakenScrollBars();
					}
				}
				break;
			case MotionEvent.ACTION_UP:
				if ( mTouchState == TOUCH_STATE_SCROLLING ) {
					final VelocityTracker velocityTracker = mVelocityTracker;
					velocityTracker.computeCurrentVelocity( 1000, mMaximumVelocity );
					final int velocityX = (int) velocityTracker.getXVelocity( mActivePointerId );

					final int screenWidth = getWidth();
					final int whichScreen = ( getScrollX() + ( screenWidth / 2 ) ) / screenWidth;
					final float scrolledPos = (float) getScrollX() / screenWidth;

					if ( velocityX > SNAP_VELOCITY && mCurrentScreen > 0 ) {
						// Fling hard enough to move left.
						// Don't fling across more than one screen at a time.
						final int bound = scrolledPos < whichScreen ? mCurrentScreen - 1 : mCurrentScreen;
						snapToScreen( Math.min( whichScreen, bound ), velocityX, true );
					} else if ( velocityX < -SNAP_VELOCITY && mCurrentScreen < mItemCount - 1 ) {
						// Fling hard enough to move right
						// Don't fling across more than one screen at a time.
						final int bound = scrolledPos > whichScreen ? mCurrentScreen + 1 : mCurrentScreen;
						snapToScreen( Math.max( whichScreen, bound ), velocityX, true );
					} else {
						snapToScreen( whichScreen, 0, true );
					}

					if ( mEdgeGlowLeft != null ) {
						mEdgeGlowLeft.onRelease();
						mEdgeGlowRight.onRelease();
					}
				}
				mTouchState = TOUCH_STATE_REST;
				mActivePointerId = INVALID_POINTER;
				releaseVelocityTracker();
				break;
			case MotionEvent.ACTION_CANCEL:
				if ( mTouchState == TOUCH_STATE_SCROLLING ) {
					final int screenWidth = getWidth();
					final int whichScreen = ( getScrollX() + ( screenWidth / 2 ) ) / screenWidth;
					snapToScreen( whichScreen, 0, true );
				}
				mTouchState = TOUCH_STATE_REST;
				mActivePointerId = INVALID_POINTER;
				releaseVelocityTracker();

				if ( mEdgeGlowLeft != null ) {
					mEdgeGlowLeft.onRelease();
					mEdgeGlowRight.onRelease();
				}

				break;
			case MotionEvent.ACTION_POINTER_UP:
				onSecondaryPointerUp( ev );
				break;
		}

		return true;
	}

	private void acquireVelocityTrackerAndAddMovement( MotionEvent ev ) {
		if ( mVelocityTracker == null ) {
			mVelocityTracker = VelocityTracker.obtain();
		}
		mVelocityTracker.addMovement( ev );
	}

	private void releaseVelocityTracker() {
		if ( mVelocityTracker != null ) {
			mVelocityTracker.recycle();
			mVelocityTracker = null;
		}
	}

	void snapToScreen( int whichScreen ) {
		snapToScreen( whichScreen, 0, false );
	}

	private void snapToScreen( int whichScreen, int velocity, boolean settle ) {
		// if (!mScroller.isFinished()) return;

		whichScreen = Math.max( 0, Math.min( whichScreen, mItemCount - 1 ) );

		enableChildrenCache( mCurrentScreen, whichScreen );

		mNextScreen = whichScreen;

		mIndicator.setLevel( mNextScreen, mItemCount );

		View focusedChild = getFocusedChild();
		if ( focusedChild != null && whichScreen != mCurrentScreen && focusedChild == getChildAt( mCurrentScreen ) ) {
			focusedChild.clearFocus();
		}

		final int screenDelta = Math.max( 1, Math.abs( whichScreen - mCurrentScreen ) );
		final int newX = whichScreen * getWidth();
		final int delta = newX - getScrollX();
		int duration = ( screenDelta + 1 ) * 100;

		if ( !mScroller.isFinished() ) {
			mScroller.abortAnimation();
		}

		if ( settle ) {
			mScrollInterpolator.setDistance( screenDelta );
		} else {
			mScrollInterpolator.disableSettle();
		}

		velocity = Math.abs( velocity );
		if ( velocity > 0 ) {
			duration += ( duration / ( velocity / BASELINE_FLING_VELOCITY ) ) * FLING_VELOCITY_INFLUENCE;
		} else {
			duration += 100;
		}

		awakenScrollBars( duration );
		mScroller.startScroll( getScrollX(), 0, delta, 0, duration );

		int mode = getOverScroll();
		
		if ( delta != 0 && ( mode == OVER_SCROLL_IF_CONTENT_SCROLLS ) ) {
			edgeReached( whichScreen, delta, velocity );
		}

		invalidate();
	}

	void edgeReached( int whichscreen, int delta, int vel ) {

		if ( whichscreen == 0 || whichscreen == ( mItemCount - 1 ) ) {
			if ( delta < 0 ) {
				mEdgeGlowLeft.onAbsorb( vel );
			} else {
				mEdgeGlowRight.onAbsorb( vel );
			}
		}
	}

	@Override
	protected Parcelable onSaveInstanceState() {
		Log.e( TAG, "onSaveInstanceState" );
		final SavedState state = new SavedState( super.onSaveInstanceState() );
		state.currentScreen = mCurrentScreen;
		return state;
	}

	@Override
	protected void onRestoreInstanceState( Parcelable state ) {
		Log.e( TAG, "onRestoreInstanceState" );
		SavedState savedState = (SavedState) state;
		super.onRestoreInstanceState( savedState.getSuperState() );
		if ( savedState.currentScreen != -1 ) {
			mCurrentScreen = savedState.currentScreen;
			Launcher.setScreen( mCurrentScreen );
		}
	}

	public void scrollLeft() {
		if ( mScroller.isFinished() ) {
			if ( mCurrentScreen > 0 ) snapToScreen( mCurrentScreen - 1 );
		} else {
			if ( mNextScreen > 0 ) snapToScreen( mNextScreen - 1 );
		}
	}

	public void scrollRight() {
		if ( mScroller.isFinished() ) {
			if ( mCurrentScreen < mItemCount - 1 ) snapToScreen( mCurrentScreen + 1 );
		} else {
			if ( mNextScreen < mItemCount - 1 ) snapToScreen( mNextScreen + 1 );
		}
	}

	public int getScreenForView( View v ) {
		int result = -1;
		if ( v != null ) {
			ViewParent vp = v.getParent();
			int count = mItemCount;
			for ( int i = 0; i < count; i++ ) {
				if ( vp == getChildAt( i ) ) {
					return i;
				}
			}
		}
		return result;
	}

	public View getViewForTag( Object tag ) {
		int screenCount = mItemCount;
		for ( int screen = 0; screen < screenCount; screen++ ) {
			CellLayout currentScreen = ( (CellLayout) getChildAt( screen ) );
			int count = currentScreen.getChildCount();
			for ( int i = 0; i < count; i++ ) {
				View child = currentScreen.getChildAt( i );
				if ( child.getTag() == tag ) {
					return child;
				}
			}
		}
		return null;
	}

	/**
	 * @return True is long presses are still allowed for the current touch
	 */
	public boolean allowLongPress() {
		return mAllowLongPress;
	}

	/**
	 * Set true to allow long-press events to be triggered, usually checked by {@link Launcher} to accept or block dpad-initiated
	 * long-presses.
	 */
	public void setAllowLongPress( boolean allowLongPress ) {
		mAllowLongPress = allowLongPress;
	}

	void moveToDefaultScreen( boolean animate ) {
		if ( animate ) {
			snapToScreen( mDefaultScreen );
		} else {
			setCurrentScreen( mDefaultScreen );
		}
		getChildAt( mDefaultScreen ).requestFocus();
	}

	void setIndicator( WorkspaceIndicator indicator ) {
		mIndicator = indicator;
		mIndicator.setLevel( mCurrentScreen, mItemCount );
	}

	public static class SavedState extends BaseSavedState {

		int currentScreen = -1;

		SavedState( Parcelable superState ) {
			super( superState );
		}

		private SavedState( Parcel in ) {
			super( in );
			currentScreen = in.readInt();
		}

		@Override
		public void writeToParcel( Parcel out, int flags ) {
			super.writeToParcel( out, flags );
			out.writeInt( currentScreen );
		}

		public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {

			@Override
			public SavedState createFromParcel( Parcel in ) {
				return new SavedState( in );
			}

			@Override
			public SavedState[] newArray( int size ) {
				return new SavedState[size];
			}
		};
	}

	class WorkspaceDataSetObserver extends DataSetObserver {

		@Override
		public void onChanged() {
			Log.i( TAG, "WorkspaceDataSetObserver::onChanged" );
			super.onChanged();
		}

		@Override
		public void onInvalidated() {
			Log.i( TAG, "WorkspaceDataSetObserver::onInvalidated" );
			super.onInvalidated();
		}
	}

	class RecycleBin {

		protected View[] array;
		protected int start, end, maxSize;
		protected boolean full;

		public RecycleBin( int size ) {
			maxSize = size;
			array = new View[size];
			start = end = 0;
			full = false;
		}

		public boolean isEmpty() {
			return ( ( start == end ) && !full );
		}

		public void add( View o ) {
			if ( !full ) array[start = ( ++start % array.length )] = o;
			if ( start == end ) full = true;
		}

		public View remove() {
			if ( full )
				full = false;
			else if ( isEmpty() ) return null;
			return array[end = ( ++end % array.length )];
		}

		void clear() {
			while ( !isEmpty() ) {
				final View view = remove();
				if ( view != null ) {
					removeDetachedView( view, true );
				}
			}
			array = new View[maxSize];
			start = end = 0;
			full = false;
		}
	}
}
