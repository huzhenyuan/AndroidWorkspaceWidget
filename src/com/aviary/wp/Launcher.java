package com.aviary.wp;

import java.util.List;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import com.aviary.wp.CellLayout.CellInfo;

public class Launcher extends Activity {

	private Workspace mWorkspace;
	private WorkspaceIndicator mIndicator;

	@Override
	public void onCreate( Bundle savedInstanceState ) {
		super.onCreate( savedInstanceState );
		setContentView( R.layout.launcher );
		setupViews();
		initAdapter();
	}

	public void previousScreen( View v ) {
		mWorkspace.scrollLeft();
	}

	public void nextScreen( View v ) {
		mWorkspace.scrollRight();
	}

	private int mOrientation;

	@Override
	protected void onStart() {
		super.onStart();
		mOrientation = getWindowManager().getDefaultDisplay().getOrientation();
	}

	@Override
	public void onConfigurationChanged( Configuration newConfig ) {

		Log.i( Workspace.TAG, "onConfigurationChanged: " + newConfig.orientation + ", old orientation: " + mOrientation );

		super.onConfigurationChanged( newConfig );

		if ( mOrientation != newConfig.orientation ) {
			mOrientation = newConfig.orientation;
			initAdapter();
		}
	}

	private void setupViews() {

		mWorkspace = (Workspace) findViewById( R.id.workspace );
		mIndicator = (WorkspaceIndicator) findViewById( R.id.indicator );

		final Workspace workspace = mWorkspace;
		workspace.setHapticFeedbackEnabled( false );
		mWorkspace.setIndicator( mIndicator );
	}

	public Workspace getWorkspace() {
		return mWorkspace;
	}

	void initAdapter() {

		Log.d( Workspace.TAG, "initAdapter" );

		List<ApplicationInfo> apps = getPackageManager().getInstalledApplications( PackageManager.GET_META_DATA );
		WorkspaceAdapter adapter = new WorkspaceAdapter( this, R.layout.workspace_screen, apps.subList( 0, 40 ) );
		mWorkspace.setAdapter( adapter );
	}

	public void startActivitySafely( Intent intent, String string ) {}

	public static void setScreen( int mCurrentScreen ) {}

	class WorkspaceAdapter extends ArrayAdapter<ApplicationInfo> {

		int screenId;
		PackageManager pm;
		private LayoutInflater mInflater;
		private int nCellsPerScreen = 4;

		public WorkspaceAdapter( Context context, int textViewResourceId, List<ApplicationInfo> objects ) {
			super( context, textViewResourceId, objects );
			screenId = textViewResourceId;
			pm = context.getPackageManager();
			nCellsPerScreen = context.getResources().getInteger( R.integer.config_portraitCells )
					* context.getResources().getInteger( R.integer.config_portraitRows );

			DisplayMetrics metrics = getResources().getDisplayMetrics();
			if ( metrics.widthPixels > 1024 ) {
				nCellsPerScreen = metrics.widthPixels / 100;
			}

			mInflater = (LayoutInflater) context.getSystemService( LAYOUT_INFLATER_SERVICE );
		}

		@Override
		public int getCount() {
			return (int) Math.ceil( (double) super.getCount() / nCellsPerScreen );
		}

		public int getRealCount() {
			return super.getCount();
		}

		@Override
		public View getView( int position, View convertView, ViewGroup parent ) {

			Log.i( Workspace.TAG, "WorkspaceAdapter.getView: " + position );

			if ( convertView == null ) {
				convertView = mInflater.inflate( screenId, mWorkspace, false );
			}

			CellLayout cell = (CellLayout) convertView;
			cell.setNumCols( nCellsPerScreen );

			int index = position * nCellsPerScreen;
			int realCount = getRealCount();

			for ( int i = 0; i < nCellsPerScreen; i++ ) {
				CellInfo cellInfo = cell.findVacantCell( 1, 1 );
				TextView text;

				if ( cellInfo == null ) {
					text = (TextView) cell.getChildAt( i );
				} else {
					text = (TextView) mInflater.inflate( R.layout.application_boxed, cell, false );
					CellLayout.LayoutParams lp = new CellLayout.LayoutParams( cellInfo.cellX, cellInfo.cellY, cellInfo.spanH,
							cellInfo.spanV );
					cell.addView( text, i, lp );
				}

				if ( index + i < realCount ) {
					ApplicationInfo appInfo = getItem( index + i );
					CharSequence label = appInfo.loadLabel( pm );
					Drawable bm = appInfo.loadIcon( pm );

					text.setCompoundDrawablesWithIntrinsicBounds( null, bm, null, null ); // new
					text.setText( label );
					text.setClickable( true );
					text.setFocusable( true );
					text.setVisibility( View.VISIBLE );
				} else {
					text.setVisibility( View.INVISIBLE );
				}
			}
			return convertView;
		}
	}
}