package com.aviary.wp;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.LinearLayout;

public class WorkspaceIndicator extends LinearLayout {

	int mResId;
	int mSelected;

	public WorkspaceIndicator( Context context, AttributeSet attrs ) {
		super( context, attrs );
		init( context, attrs, 0 );
	}

	private void init( Context context, AttributeSet attrs, int defStyle ) {
		TypedArray a = context.obtainStyledAttributes( attrs, R.styleable.WorkspaceIndicator, defStyle, 0 );

		setOrientation( LinearLayout.HORIZONTAL );

		mResId = a.getResourceId( R.styleable.WorkspaceIndicator_indicatorId, 0 );

		if ( mResId != 0 ) {
			context.getResources().getDrawable( mResId );
		}

		a.recycle();
	}

	void resetView( int count ) {
		removeAllViews();
		
		if ( mResId != 0 && count > 0 ) {

			for ( int i = 0; i < count; i++ ) {
				ImageView v = new ImageView( getContext() );
				v.setImageResource( mResId );
				v.setSelected( false );
				v.setPadding( 1, 0, 1, 0 );
				addView( v );
			}
		}
	}

	public void setLevel( int mCurrentScreen, int mItemCount ) {

		if ( getChildCount() != mItemCount ) {
			resetView( mItemCount );
			mSelected = 0;
		}

		if ( mCurrentScreen >= 0 && mCurrentScreen < getChildCount() ){
			getChildAt( mSelected ).setSelected( false );
			getChildAt( mCurrentScreen ).setSelected( true );
			mSelected = mCurrentScreen;
		}
	}

}
