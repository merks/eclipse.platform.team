/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.compare; 

import java.lang.reflect.InvocationTargetException;

import java.util.List;
import java.util.ArrayList;
import java.util.ResourceBundle;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.graphics.Image;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.*;
import org.eclipse.ui.IPersistableElement;
import org.eclipse.ui.IEditorInput;

import org.eclipse.jface.util.*;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.jface.viewers.*;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.resource.ImageDescriptor;

import org.eclipse.compare.contentmergeviewer.ContentMergeViewer;
import org.eclipse.compare.internal.*;
import org.eclipse.compare.structuremergeviewer.*;


/**
 * A compare operation which can present its results in a special editor.
 * Running the compare operation and presentating the results in a compare editor
 * are combined in one interface because it allows a client to keep the implementation
 * all in one place while separating it from the innards of a specific UI implementation of compare/merge.
 * <p> 
 * A <code>CompareEditorInput</code> defines methods for the following sequence steps:
 * <UL>
 * <LI>running a lengthy compare operation under progress monitor control,
 * <LI>creating a UI for displaying the model and initializing the some widgets with the compare result,
 * <LI>tracking the dirty state of the model in case of merge,
 * <LI>saving the model.
 * </UL>
 * The Compare plug-in's <code>openCompareEditor</code> method takes an <code>ICompareEditorInput</code>
 * and starts sequencing through the above steps. If the compare result is not empty a new compare editor
 * is opened and takes over the sequence until eventually closed.
 * <p>
 * The <code>prepareInput</code> method should contain the
 * code of the compare operation. It is executed under control of a progress monitor
 * and can be canceled. If the result of the compare is not empty, that is if there are differences
 * that needs to be presented, the <code>ICompareEditorInput</code> should hold onto them and return them with
 * the <code>getCompareResult</code> method.
 * If the value returned from <code>getCompareResult</code> is not <code>null</code>
 * a compare editor is opened on the <code>ICompareEditorInput</code> with title and title image initialized by the
 * corresponding methods of the <code>ICompareEditorInput</code>.
 * <p>
 * Creation of the editor's SWT controls is delegated to the <code>createContents</code> method.
 * Here the SWT controls must be created and initialized  with the result of the compare operation.
 * <p>
 * If merging is allowed, the modification state of the compared constituents must be tracked and the dirty
 * state returned from method <code>isSaveNeeded</code>. The value <code>true</code> triggers a subsequent call
 * to <code>save</code> where the modified resources can be saved.
 * <p>
 * The most important part of this implementation is the setup of the compare/merge UI.
 * The UI uses a simple browser metaphor to present compare results.
 * The top half of the layout shows the structural compare results (e.g. added, deleted, and changed files),
 * the bottom half the content compare results (e.g. textual differences between two files).
 * A selection in the top pane is fed to the bottom pane. If a content viewer is registered
 * for the type of the selected object, this viewer is installed in the pane.
 * In addition if a structure viewer is registered for the selection type the top pane
 * is split horizontally to make room for another pane and the structure viewer is installed
 * in it. When comparing Java files this second structure viewer would show the structural
 * differences within a Java file, e.g. added, deleted or changed methods and fields.
 * <p>
 * Subclasses provide custom setups, e.g. for a Catchup/Release operation
 * by passing a subclass of <code>CompareConfiguration</code> and by implementing the <code>prepareInput</code> method.
 * If a subclass cannot use the <code>DiffTreeViewer</code> which is installed by default in the
 * top left pane, method <code>createDiffViewer</code> can be overridden.
 * 
 * @see CompareUI
 * @see CompareEditorInput
 */
public abstract class CompareEditorInput implements IEditorInput, IPropertyChangeNotifier, IRunnableWithProgress {
	
	
	/**
	 * The name of the "dirty" property.
	 */
	public static final String DIRTY_STATE= "DIRTY_STATE"; //$NON-NLS-1$
		
	private static final String COMPARE_EDITOR_IMAGE_NAME= "cview16/compare_view.gif"; //$NON-NLS-1$
	private static Image fgTitleImage;
	
	private Splitter fComposite;
	private CompareConfiguration fCompareConfiguration;
	private CompareViewerSwitchingPane fStructureInputPane;
	private CompareViewerSwitchingPane fStructurePane1;
	private CompareViewerSwitchingPane fStructurePane2;
	private CompareViewerSwitchingPane fContentInputPane;
	private CompareViewerSwitchingPane fFocusPane;
	private String fMessage;
	private ISelection fSelection2;
	private Object fInput;
	private String fTitle;
	private ListenerList fListenerList= new ListenerList();
	private CompareNavigator fNavigator;
	private boolean fDirty= false;
	private IPropertyChangeListener fDirtyStateListener;

	private IgnoreWhiteSpaceAction fIgnoreWhitespace;
	private ShowPseudoConflicts fShowPseudoConflicts;
	
	boolean fStructureCompareOnSingleClick= false;

	/**
	 * Creates a <code>CompareEditorInput</code> which is initialized with the given
	 * compare configuration.
	 * The compare configuration is passed to subsequently created viewers.
	 *
	 * @param configuration the compare configuration 
	 */
	public CompareEditorInput(CompareConfiguration configuration) {
		fCompareConfiguration= configuration;
		Assert.isNotNull(configuration);
		
		ResourceBundle bundle= CompareUIPlugin.getResourceBundle();
		fIgnoreWhitespace= new IgnoreWhiteSpaceAction(bundle, configuration);
		fShowPseudoConflicts= new ShowPseudoConflicts(bundle, configuration);
		
		fDirtyStateListener= new IPropertyChangeListener() {
			public void propertyChange(PropertyChangeEvent e) {
				if (Utilities.getValue(e, false))
					setDirty(true);
			}
		};
	}
	
	private boolean structureCompareOnSingleClick() {
		return fStructureCompareOnSingleClick;
	}
		
	/* (non Javadoc)
	 * see IAdaptable.getAdapter
	 */
	public Object getAdapter(Class adapter) {
		if (CompareNavigator.class.equals(adapter)) {
			if (fNavigator == null)
				fNavigator= new CompareNavigator(
					new CompareViewerSwitchingPane[] {
						fStructureInputPane,
						fStructurePane1,
						fStructurePane2,
						fContentInputPane
					}
				);
			return fNavigator;
		}
		return null;
	}
	
	/* (non Javadoc)
	 * see IEditorInput.getImageDescriptor
	 */
	public ImageDescriptor getImageDescriptor() {
		return null;
	}
	
	/* (non Javadoc)
	 * see IEditorInput.getToolTipText
	 */
	public String getToolTipText() {
		return fTitle;
	}
	
	/* (non Javadoc)
	 * see IEditorInput.getName
	 */
	public String getName() {
		return fTitle;
	}
			
	/**
	 * Returns <code>null</code> since this editor cannot be persisted.
	 *
	 * @return <code>null</code> because this editor cannot be persisted
	 */
	public IPersistableElement getPersistable() {
		return null;
	}
		
	/**
	 * Returns <code>false</code> to indicate that this input
	 * should not appear in the "File Most Recently Used" menu.
	 *
	 * @return <code>false</code>
	 */
	public boolean exists() {
		return false;
	}
	
	/*
	 * FIXME!
 	 */
	protected void setMessage(String message) {
		fMessage= message;
	}
	
	/*
	 * FIXME!
 	 */
	public String getMessage() {
		return fMessage;
	}
				
	/**
	 * Returns the title which will be used in the compare editor's title bar.
	 * It can be set with <code>setTitle</code>.
	 *
	 * @return the title
	 */
	public String getTitle() {
		return fTitle;
	}
	
	/**
	 * Sets the title which will be used when presenting the compare result.
	 * This method must be called before the editor is opened.
	 * 
	 * @param title the title to use for the CompareEditor
	 */
	public void setTitle(String title) {
		fTitle= title;
	}
	
	/**
	 * Returns the title image which will be used in the compare editor's title bar.
	 * Returns the title image which will be used when presenting the compare result.
	 * This implementation returns a generic compare icon.
	 * Subclasses can override.
	 *
	 * @return the title image, or <code>null</code> if none
	 */
	public Image getTitleImage() {
		if (fgTitleImage == null) {
			fgTitleImage= CompareUIPlugin.getImageDescriptor(COMPARE_EDITOR_IMAGE_NAME).createImage();
			CompareUI.disposeOnShutdown(fgTitleImage);
		}
		return fgTitleImage;
	}
	
	/**
	 * Returns the configuration object for the viewers within the compare editor.
	 * Returns the configuration which was passed to the constructor.
	 *
	 * @return the compare configuration
	 */
	public CompareConfiguration getCompareConfiguration() {
		return fCompareConfiguration;
	}

	/**
	 * Adds standard actions to the given <code>ToolBarManager</code>.
	 * <p>
	 * Subclasses may override to add their own actions.
	 * </p>
	 *
	 * @param toolBarManager the <code>ToolBarManager</code> to which to contribute
	 */
	public void contributeToToolBar(ToolBarManager toolBarManager) {
		
		toolBarManager.add(new Separator());
		toolBarManager.add(fIgnoreWhitespace);
		toolBarManager.add(fShowPseudoConflicts);
	}
	
	/**
	 * Runs the compare operation and stores the compare result.
	 *
	 * @param monitor the progress monitor to use to display progress and receive
	 *   requests for cancelation
	 * @exception InvocationTargetException if the <code>prepareInput</code> method must propagate a checked exception,
	 * 	it should wrap it inside an <code>InvocationTargetException</code>; runtime exceptions are automatically
	 *  wrapped in an <code>InvocationTargetException</code> by the calling context
	 * @exception InterruptedException if the operation detects a request to cancel, 
	 *  using <code>IProgressMonitor.isCanceled()</code>, it should exit by throwing 
	 *  <code>InterruptedException</code>
	 */
	public void run(IProgressMonitor monitor) throws InterruptedException, InvocationTargetException {
		fInput= prepareInput(monitor);
	}

	/**
	 * Runs the compare operation and returns the compare result.
	 * If <code>null</code> is returned no differences were found and no compare editor needs to be opened.
	 * Progress should be reported to the given progress monitor.
	 * A request to cancel the operation should be honored and acknowledged 
	 * by throwing <code>InterruptedException</code>.
	 *
	 * @param monitor the progress monitor to use to display progress and receive
	 *   requests for cancelation
	 * @return the result of the compare operation, or <code>null</code> if there are no differences
	 * @exception InvocationTargetException if the <code>prepareInput</code> method must propagate a checked exception,
	 * 	it should wrap it inside an <code>InvocationTargetException</code>; runtime exceptions are automatically
	 *  wrapped in an <code>InvocationTargetException</code> by the calling context
	 * @exception InterruptedException if the operation detects a request to cancel, 
	 *  using <code>IProgressMonitor.isCanceled()</code>, it should exit by throwing 
	 *  <code>InterruptedException</code>
	 */
	protected abstract Object prepareInput(IProgressMonitor monitor)
				throws InvocationTargetException, InterruptedException;
	 
	/**
	 * Returns the compare result computed by the most recent call to the
	 * <code>run</code> method. Returns <code>null</code> if no
	 * differences were found.
	 *
	 * @return the compare result prepared in method <code>prepareInput</code>
	 *   or <code>null</code> if there were no differences
	 */
	public Object getCompareResult() {
		return fInput;
	}
	
	/**
	 * Create the SWT controls that are used to display the result of the compare operation.
	 * Creates the SWT Controls and sets up the wiring between the individual panes.
	 * This implementation creates all four panes but makes only the necessary ones visible.
	 * Finally it feeds the compare result into the top left structure viewer
	 * and the content viewer.
	 * <p>
	 * Subclasses may override if they need to change the layout or wiring between panes.
	 *
	 * @param parent the parent control under which the control must be created
	 * @return the SWT control hierarchy for the compare editor
	 */
	public Control createContents(Composite parent) {

		fComposite= new Splitter(parent, SWT.VERTICAL);
		fComposite.setData(this);
			
		final Splitter h= new Splitter(fComposite, SWT.HORIZONTAL);

		fStructureInputPane= new CompareViewerSwitchingPane(h, SWT.BORDER | SWT.FLAT, true) {
			protected Viewer getViewer(Viewer oldViewer, Object input) {
				if (input instanceof DiffNode) {
					DiffNode dn= (DiffNode) input;
					if (dn.hasChildren())
						return createDiffViewer(this);
				}
				if (input instanceof ICompareInput)
					return findStructureViewer(oldViewer, (ICompareInput)input, this);
				return null;
			}
		};
		fFocusPane= fStructureInputPane;
		
		fStructurePane1= new CompareViewerSwitchingPane(h, SWT.BORDER | SWT.FLAT, true) {
			protected Viewer getViewer(Viewer oldViewer, Object input) {
				if (input instanceof ICompareInput)
					return findStructureViewer(oldViewer, (ICompareInput)input, this);
				return null;
			}
		};
		h.setVisible(fStructurePane1, false);
		
		fStructurePane2= new CompareViewerSwitchingPane(h, SWT.BORDER | SWT.FLAT, true) {
			protected Viewer getViewer(Viewer oldViewer, Object input) {
				if (input instanceof ICompareInput)
					return findStructureViewer(oldViewer, (ICompareInput)input, this);
				return null;
			}
		};
		h.setVisible(fStructurePane2, false);
				
		fContentInputPane= new CompareViewerSwitchingPane(fComposite, SWT.BORDER | SWT.FLAT) {
			protected Viewer getViewer(Viewer oldViewer, Object input) {
				if (input instanceof ICompareInput)
					return findContentViewer(oldViewer, (ICompareInput)input, this);
				return null;
			}
		};
		fComposite.setVisible(h, false);
		fComposite.setVisible(fContentInputPane, true);
		
		fComposite.setWeights(new int[] { 30, 70 });
		
		fComposite.layout();

		// setup the wiring for top left pane
		fStructureInputPane.addSelectionChangedListener(
			new ISelectionChangedListener() {
				public void selectionChanged(SelectionChangedEvent e) {
					feed1(e.getSelection());
				}
			}
		);
		
		if (!structureCompareOnSingleClick()) {
			fStructureInputPane.addDoubleClickListener(
				new IDoubleClickListener() {
					public void doubleClick(DoubleClickEvent e) {
						feedDefault1(e.getSelection());
					}
				}
			);
		}

		// setup the wiring for second pane
		fStructurePane1.addSelectionChangedListener(
			new ISelectionChangedListener() {
				public void selectionChanged(SelectionChangedEvent e) {
					feed2(e.getSelection());
				}
			}
		);

		// setup the wiring for third pane
		fStructurePane2.addSelectionChangedListener(
			new ISelectionChangedListener() {
				public void selectionChanged(SelectionChangedEvent e) {
					feed3(e.getSelection());
				}
			}
		);
		
		// now deal with activation
		Listener activationListener= new Listener() {
			public void handleEvent(Event event) {
				if (event.widget instanceof CompareViewerSwitchingPane) {
					fFocusPane= (CompareViewerSwitchingPane) event.widget;
				}
			}
		};
		fStructureInputPane.addListener(SWT.Activate, activationListener);
		fStructurePane1.addListener(SWT.Activate, activationListener);
		fStructurePane2.addListener(SWT.Activate, activationListener);
		fContentInputPane.addListener(SWT.Activate, activationListener);
	
		if (fInput instanceof ICompareInput) {
			ICompareInput input2= (ICompareInput) fInput;
			fStructureInputPane.setInput(input2);
			feed1(fStructureInputPane.getSelection());
		}
		
		fComposite.setData("Nav", //$NON-NLS-1$
			new CompareViewerSwitchingPane[] {
				fStructureInputPane,
				fStructurePane1,
				fStructurePane2,
				fContentInputPane
			}
		);
	
		return fComposite;
	}
	
	private void feed1(final ISelection selection) {
		BusyIndicator.showWhile(fComposite.getDisplay(),
			new Runnable() {
				public void run() {
					if (selection == null || selection.isEmpty()) {
						Object input= fStructureInputPane.getInput();
						fContentInputPane.setInput(input);
						fStructurePane2.setInput(null); // clear downstream pane
						fStructurePane1.setInput(null);
					} else {
						Object input= getElement(selection);
						if (structureCompareOnSingleClick()) {
							fStructurePane1.setInput(input);
							//if (fStructurePane1.isEmpty())
								fContentInputPane.setInput(input);
						} else {
							fContentInputPane.setInput(input);
						}
						fStructurePane2.setInput(null); // clear downstream pane
						if (fStructurePane1.getInput() != input)
							fStructurePane1.setInput(null);
					}
				}
			}
		);
	}
	
	private void feedDefault1(final ISelection selection) {
		BusyIndicator.showWhile(fComposite.getDisplay(),
			new Runnable() {
				public void run() {
					if (!selection.isEmpty())
						fStructurePane1.setInput(getElement(selection));
				}
			}
		);
	}
	
	private void feed2(final ISelection selection) {
		BusyIndicator.showWhile(fComposite.getDisplay(),
			new Runnable() {
				public void run() {
					if (selection.isEmpty()) {
						Object input= fStructurePane1.getInput();
						fContentInputPane.setInput(input);
						fStructurePane2.setInput(null);
					} else {
						Object input= getElement(selection);
						fContentInputPane.setInput(input);
						fStructurePane2.setInput(input);
					}
				}
			}
		);
	}
	
	private void feed3(final ISelection selection) {
		BusyIndicator.showWhile(fComposite.getDisplay(),
			new Runnable() {
				public void run() {
					if (selection.isEmpty())
						fContentInputPane.setInput(fStructurePane2.getInput());
					else
						fContentInputPane.setInput(getElement(selection));
				}
			}
		);
		
	}
	
	/**
	 * Returns the first element of the given selection if the selection 
	 * is a <code>IStructuredSelection</code> with exactly one element. Returns
	 * <code>null</code> otherwise.
	 *
	 * @param selection the selection
	 * @return the first element of the selection, or <code>null</code>
	 */
	private static Object getElement(ISelection selection) {
		if (selection instanceof IStructuredSelection) {
			IStructuredSelection ss= (IStructuredSelection) selection;
			if (ss.size() == 1)
				return ss.getFirstElement();
		}
		return null;
	}
	
	/**
	 * Asks this input to take focus within its container (editor).
	 * <p>
	 * Clients should not call this method but they may
	 * override if they implement a different layout with different visual
	 * components. Clients are free to call the inherited method.
	 * </p>
	 */
	public void setFocus() {
		if (fFocusPane != null) {
			Viewer v= fFocusPane.getViewer();
			if (v != null) {
				Control c= v.getControl();
				if (c != null)
					c.setFocus();
			}
		} else if (fComposite != null)
			fComposite.setFocus();
	}
	
	/**
	 * Factory method for creating a differences viewer for the top left pane.
	 * It is called from <code>createContents</code> and returns a <code>DiffTreeViewer</code>.
	 * <p>
	 * Subclasses may override if they need a different viewer.
	 * </p>
	 *
	 * @param parent the SWT parent control under which to create the viewer's SWT controls
	 * @return a compare viewer for the top left pane
	 */
	public Viewer createDiffViewer(Composite parent) {
		return new DiffTreeViewer(parent, fCompareConfiguration);
	}

	/**
	 * Implements the dynamic viewer switching for structure viewers.
	 * The method must return a compare viewer based on the old (or current) viewer
	 * and a new input object. If the old viewer is suitable for showing the new input the old viewer
	 * can be returned. Otherwise a new viewer must be created under the given parent composite or
	 * <code>null</code> can be returned to indicate that no viewer could be found.
	 * <p>
	 * This implementation forwards the request to <code>CompareUI.findStructureViewer</code>.
	 * <p>
	 * Subclasses may override to implement a different strategy.
	 * </p>
	 *
	 * @return a compare viewer which is suitable for the given input object or <code>null</code>
	 */
	public Viewer findStructureViewer(Viewer oldViewer, ICompareInput input, Composite parent) {
		return CompareUIPlugin.findStructureViewer(oldViewer, input, parent, fCompareConfiguration);
	}

	/**
	 * Implements the dynamic viewer switching for content viewers.
	 * The method must return a compare viewer based on the old (or current) viewer
	 * and a new input object. If the old viewer is suitable for showing the new input the old viewer
	 * can be returned. Otherwise a new viewer must be created under the given parent composite or
	 * <code>null</code> can be returned to indicate that no viewer could be found.
	 * <p>
	 * This implementation forwards the request to <code>CompareUI.findContentViewer</code>.
	 * <p>
	 * Subclasses may override to implement a different strategy.
	 * </p>
	 *
	 * @return a compare viewer which is suitable for the given input object or <code>null</code>
	 */
	public Viewer findContentViewer(Viewer oldViewer, ICompareInput input, Composite parent) {
		Viewer v= CompareUIPlugin.findContentViewer(oldViewer, input, parent, fCompareConfiguration);
		
		if (v instanceof IPropertyChangeNotifier) {
			final IPropertyChangeNotifier dsp= (IPropertyChangeNotifier) v;

			dsp.addPropertyChangeListener(fDirtyStateListener);
			
			Control c= v.getControl();
			c.addDisposeListener(
				new DisposeListener() {
					public void widgetDisposed(DisposeEvent e) {
						dsp.removePropertyChangeListener(fDirtyStateListener);
					}
				}
			);
		}
		
		return v;
	}
	
	/**
	 * Returns <code>true</code> if there are unsaved changes.
	 * The value returned is the value of the <code>DIRTY_STATE</code> property of this input object.
	 
	 * Returns <code>true</code> if this input has unsaved changes,
	 * that is if <code>setDirty(true)</code> has been called.
	 * Subclasses don't have to override if the functionality provided by <doce>setDirty</code>
	 * is sufficient.
	 *
	 * @return <code>true</code> if there are changes that need to be saved
	 */
	public boolean isSaveNeeded() {
		return fDirty;
	}
		
	/**
	 * Sets the dirty state of this input to the given
	 * value and sends out a <code>PropertyChangeEvent</code> if the new value differs from the old value.
	 *
	 * @param dirty the dirty state for this compare input
	 */
	public void setDirty(boolean dirty) {
		if (dirty != fDirty) {
			boolean old= fDirty;
			fDirty= dirty;
			Utilities.firePropertyChange(fListenerList, this, DIRTY_STATE, new Boolean(old), new Boolean(fDirty));
		}
	}
	
	/* (non Javadoc)
	 * see IPropertyChangeNotifier.addListener
	 */
	public void addPropertyChangeListener(IPropertyChangeListener listener) {
		fListenerList.add(listener);
	}

	/* (non Javadoc)
	 * see IPropertyChangeNotifier.removeListener
	 */
	public void removePropertyChangeListener(IPropertyChangeListener listener) {
		fListenerList.remove(listener);
	}

	/**
	 * Save any unsaved changes.
	 * Empty implementation.
	 * Subclasses must override to save any changes.
	 *
	 * @param progressMonitor an <code>IProgressMonitor</code> that the implementation of save may use to show progress
	 * @deprecated Override method saveChanges instead.
	 */
	public void save(IProgressMonitor pm) {
	}
	
	/**
	 * Save any unsaved changes.
	 * Subclasses must override to save any changes.
	 * This implementation tries to flush changes in all viewers by
	 * calling <code>setInput</code> on them.
	 *
	 * @param progressMonitor an <code>IProgressMonitor</code> that the implementation of save may use to show progress
	 */
	public void saveChanges(IProgressMonitor pm) throws CoreException {
		
		// flush changes in any dirty viewer
		flushViewer(fStructureInputPane);
		flushViewer(fStructurePane1);
		flushViewer(fStructurePane2);
		flushViewer(fContentInputPane);

		save(pm);
	}
	
	private static void flushViewer(CompareViewerSwitchingPane pane) {
		if (pane != null) {
			Viewer v= pane.getViewer();
			if (v != null) {
				
				// since we have already asked the user whether he wants to save
				// changes, we disable the confirmation alert in
				// ContentMergeViewer.inputChanged
				if (v instanceof ContentMergeViewer)
					((ContentMergeViewer)v).setConfirmSave(false);

				Object input= pane.getInput();
				v.setInput(input);
			}
		}
	}
}

