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
package com.projectlibre1.pm.graphic.spreadsheet.editor;

import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.SpinnerModel;
import javax.swing.SwingUtilities;

/**
 * Extension of regular spinner to handle case of spreadsheet cell activation by keystroke
 * TODO figure out how to handle double clicks. The treatment I use with Simple edits doesnt work
 */
public class KeyboardFocusSpinner extends JSpinner  implements KeyboardFocusable {
		public KeyboardFocusSpinner(SpinnerModel arg0) {
			super(arg0);
			listenToTextField();
			// This code below doesn't work
//			getTextField().addMouseListener(new MouseAdapter() {
//				public void mousePressed(MouseEvent e) {
//					if (e.getClickCount() == 2)
//						MainFrame.getInstance().doInformationDialog(false);
//				}
//			});

		}
		
		/**
		 * There is an annoying problem with the first character not being sent to the editor.  The
		 * code below sends the first dsplayable chararacter to the editor.  Subsequent characters will
		 * implicitly go to the editor.
		 */
		protected boolean processKeyBinding(KeyStroke arg0, KeyEvent arg1, int arg2, boolean arg3) {
			if (Character.isDefined(arg0.getKeyChar())) {
				getTextField().dispatchEvent(arg1);
				return true; // stop routing
			}
			return super.processKeyBinding(arg0, arg1, arg2, arg3);
		}
		public void requestFocus() { // override default needed otherwise key handling is wrong (backspace, arrows
			getTextField().requestFocus();
		}

		
		public void selectAll(boolean keyboard) { // convenience method
			if (keyboard) {
				selectAllPending = false;
				getTextField().selectAll();
			} else {
				// Editing was started by a click that the table still has to repost to the text field:
				// the caret moves to the click point on release, and the formatted field re-formats its
				// text when it gains focus. Select after each of those, as long as nothing was typed.
				selectAllPending = true;
				selectAllLater();
			}
		}

		private boolean selectAllPending = false;
		private JFormattedTextField listenedTextField = null;

		public void setEditor(JComponent editor) {
			super.setEditor(editor);
			listenToTextField();
		}

		private void listenToTextField() {
			JFormattedTextField textField;
			try {
				textField = getTextField();
			} catch (ClassCastException e) {
				return;
			}
			if (textField == null || textField == listenedTextField)
				return;
			listenedTextField = textField;
			textField.addMouseListener(new MouseAdapter() {
				public void mouseReleased(MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e))
						selectAllIfPending();
				}
			});
			textField.addFocusListener(new FocusAdapter() {
				public void focusGained(FocusEvent e) {
					selectAllLater(); // after JFormattedTextField has re-formatted
				}
			});
			textField.addKeyListener(new KeyAdapter() {
				public void keyTyped(KeyEvent e) {
					selectAllPending = false; // the user has typed: leave the text alone from now on
				}
			});
		}

		private void selectAllLater() {
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					selectAllIfPending();
				}
			});
		}

		private void selectAllIfPending() {
			if (!selectAllPending)
				return;
			getTextField().selectAll();
		}
		
		JFormattedTextField getTextField() { // convenience method
			return ((JSpinner.NumberEditor)getEditor()).getTextField();
		}
		
		
		
	}
