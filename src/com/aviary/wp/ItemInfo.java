package com.aviary.wp;

public abstract class ItemInfo {

	static final int NO_ID = -1;
	long id = NO_ID;
	int itemType;
	long container = NO_ID;
	int screen = -1;
	int cellX = -1;
	int cellY = -1;
	int spanX = 1;
	int spanY = 1;

	ItemInfo() {}

	ItemInfo( ItemInfo info ) {
		id = info.id;
		cellX = info.cellX;
		cellY = info.cellY;
		spanX = info.spanX;
		spanY = info.spanY;
		screen = info.screen;
		itemType = info.itemType;
		container = info.container;
	}

}
