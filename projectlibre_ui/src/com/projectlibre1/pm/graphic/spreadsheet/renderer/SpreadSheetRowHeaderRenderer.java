/*******************************************************************************
 * The contents of this file are subject to the Common Public Attribution License 
 * Version 1.0 (the "License"); you may not use this file except in compliance with 
 * the License. You may obtain a copy of the License at 
 * http://www.projectlibre.com/license . The License is based on the Mozilla Public 
 * License Version 1.1 but Sections 14 and 15 have been added to cover use of 
 * software over a computer network and provide for limited attribution for the 
 * Original Developer. In addition, Exhibit A has been modified to be consistent 
 * with Exhibit B. 
 *
 * Software distributed under the License is distributed on an "AS IS" basis, 
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for the 
 * specific language governing rights and limitations under the License. The 
 * Original Code is ProjectLibre. The Original Developer is the Initial Developer 
 * and is ProjectLibre Inc. All portions of the code written by ProjectLibre are 
 * Copyright (c) 2012-2019. All Rights Reserved. All portions of the code written by 
 * ProjectLibre are Copyright (c) 2012-2019. All Rights Reserved. Contributor 
 * ProjectLibre, Inc.
 *
 * Alternatively, the contents of this file may be used under the terms of the 
 * ProjectLibre End-User License Agreement (the ProjectLibre License) in which case 
 * the provisions of the ProjectLibre License are applicable instead of those above. 
 * If you wish to allow use of your version of this file only under the terms of the 
 * ProjectLibre License and not to allow others to use your version of this file 
 * under the CPAL, indicate your decision by deleting the provisions above and 
 * replace them with the notice and other provisions required by the ProjectLibre 
 * License. If you do not delete the provisions above, a recipient may use your 
 * version of this file under either the CPAL or the ProjectLibre Licenses. 
 *
 *
 * [NOTE: The text of this Exhibit A may differ slightly from the text of the notices 
 * in the Source Code files of the Original Code. You should use the text of this 
 * Exhibit A rather than the text found in the Original Code Source Code for Your 
 * Modifications.] 
 *
 * EXHIBIT B. Attribution Information for ProjectLibre required
 *
 * Attribution Copyright Notice: Copyright (c) 2012-2019, ProjectLibre, Inc.
 * Attribution Phrase (not exceeding 10 words): 
 * ProjectLibre, open source project management software.
 * Attribution URL: http://www.projectlibre.com
 * Graphic Image as provided in the Covered Code as file: projectlibre-logo.png with 
 * alternatives listed on http://www.projectlibre.com/logo 
 *
 * Display of Attribution Information is required in Larger Works which are defined 
 * in the CPAL as a work which combines Covered Code or portions thereof with code 
 * not governed by the terms of the CPAL. However, in addition to the other notice 
 * obligations, all copies of the Covered Code in Executable and Source Code form 
 * distributed must, as a form of attribution of the original author, include on 
 * each user interface screen the "ProjectLibre" logo visible to all users. 
 * The ProjectLibre logo should be located horizontally aligned with the menu bar 
 * and left justified on the top left of the screen adjacent to the File menu. The 
 * logo must be at least 144 x 31 pixels. When users click on the "ProjectLibre" 
 * logo it must direct them back to http://www.projectlibre.com. 
 *******************************************************************************/
package com.projectlibre1.pm.graphic.spreadsheet.renderer;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;

import com.projectlibre1.pm.graphic.frames.GraphicManager;
import com.projectlibre1.pm.graphic.model.cache.GraphicNode;
import com.projectlibre1.pm.graphic.spreadsheet.common.CommonSpreadSheet;
import com.projectlibre1.pm.graphic.spreadsheet.common.SpreadSheetRowHeader;
import com.projectlibre1.pm.graphic.spreadsheet.SpreadSheetParams;
import com.projectlibre1.field.Field;
import com.projectlibre1.util.Environment;

/**
 *
 */
public class SpreadSheetRowHeaderRenderer extends DefaultTableCellRenderer  implements OfflineRenderer{

	/**
	 *
	 */
	public SpreadSheetRowHeaderRenderer() {
		super();
	}
	Component last=null;
	private Font plainFont=null;
	private Font boldFont=null;

	/** Bold companion of the header font, derived once per font instance. */
	private Font boldVersionOf(Font font){
		if (font==null) return null;
		if (font!=plainFont){
			plainFont=font;
			boldFont=font.deriveFont(Font.BOLD);
		}
		return boldFont;
	}
	public Component getTableCellRendererComponent (JTable table, Object value,boolean isSelected, boolean hasFocus, int row, int column){
		JLabel component;
		if (table==null){
			setValue(null);
			component=this;
		}
		else{
			component=(JLabel)super.getTableCellRendererComponent(table, value, isSelected,hasFocus, row, column);

			//the row header keeps its own selection model and it is cleared whenever a cell is
			//selected in the table itself, so ask the table: that tracks every way a row gets selected
			boolean rowSelected=isSelected;
			if (!rowSelected&&table instanceof SpreadSheetRowHeader){
				CommonSpreadSheet spreadSheet=((SpreadSheetRowHeader)table).getSpreadSheet();
				if (spreadSheet!=null&&row>=0&&row<spreadSheet.getRowCount())
					rowSelected=spreadSheet.isRowSelected(row);
			}

			if (rowSelected){
				//fill the index with a dark shade of the table selection colour so the row stands out
				Color selectionBackground=UIManager.getColor("Table.selectionBackground"); //$NON-NLS-1$
				if (selectionBackground==null) selectionBackground=new Color(51,102,204);
				component.setBackground(selectionBackground.darker().darker());
				component.setForeground(Color.WHITE);
			}else{
				component.setForeground (table.getTableHeader().getForeground());
				if (Environment.isNewLaf()||Environment.isMac())
					component.setBackground(isSelected ? GraphicManager.getInstance().getLafManager().getSelectedBackgroundColor() : GraphicManager.getInstance().getLafManager().getUnselectedBackgroundColor());
				else
					component.setBackground(isSelected ? GraphicManager.getInstance().getLafManager().getSelectedBackgroundColor() : table.getTableHeader().getBackground());
			}
			Font headerFont=table.getTableHeader ().getFont();
			component.setFont (rowSelected?boldVersionOf(headerFont):headerFont);
		}
		component.setHorizontalAlignment (CENTER);
		component.setText (value == null ? "" : value.toString ());
		if (!Environment.isNewLaf())
			component.setBorder (UIManager.getBorder ("TableHeader.cellBorder"));
//			component.setBorder ((isSelected)?BorderFactory.createLoweredBevelBorder():UIManager.getBorder ("TableHeader.cellBorder"));
		component.setBorder(null);
		return component;
	}

	public Component getComponent(Object value, GraphicNode node,Field field,SpreadSheetParams params){
		JComponent component=(JComponent)getTableCellRendererComponent(null, value, false, false, -1, -1);
		component.setBorder(null);
		return component;
	}


}
