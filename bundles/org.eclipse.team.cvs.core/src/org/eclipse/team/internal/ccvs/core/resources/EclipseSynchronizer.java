package org.eclipse.team.internal.ccvs.core.resources;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ISynchronizer;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.team.ccvs.core.CVSProviderPlugin;
import org.eclipse.team.ccvs.core.CVSTag;
import org.eclipse.team.core.TeamPlugin;
import org.eclipse.team.internal.ccvs.core.CVSException;
import org.eclipse.team.internal.ccvs.core.syncinfo.FolderSyncInfo;
import org.eclipse.team.internal.ccvs.core.syncinfo.ResourceSyncInfo;

/**
 * A synchronizer is responsible for managing synchronization information for local
 * CVS resources.
 * 
 * @see ResourceSyncInfo
 * @see FolderSyncInfo
 */
public class EclipseSynchronizer {
	private static final QualifiedName FOLDER_SYNC_KEY = new QualifiedName(CVSProviderPlugin.ID, "folder-sync");
	private static final QualifiedName RESOURCE_SYNC_KEY = new QualifiedName(CVSProviderPlugin.ID, "resource-sync");
	private static EclipseSynchronizer instance;
	private int nestingCount = 0;
	private Set changedResources = new HashSet();
	private Set changedFolders = new HashSet();
	
	private EclipseSynchronizer() {
		getSynchronizer().add(RESOURCE_SYNC_KEY);
		getSynchronizer().add(FOLDER_SYNC_KEY);
	}
	
	/**
	 * Associates the provided folder sync information with the given folder. The folder
	 * must exist on the file system.
	 * <p>
	 * The workbench and team plugins are notified that the state of this resources has 
	 * changed.</p>
	 * 
	 * @param file the file or folder for which to associate the sync info.
	 * @param info the folder sync to set.
	 * 
 	 * @throws CVSException if there was a problem adding sync info.
	 */
	public void setFolderSync(IContainer folder, FolderSyncInfo info) throws CVSException {
		beginOperation();
		setCachedFolderSync(folder, info);
		changedFolders.add(folder);
		endOperation();
	}
	
	/**
	 * Answers the folder sync information associated with this folder or <code>null</code>
	 * if none is available.
	 * 
	 * @param folder the folder for which to return folder sync info.
 	 * @throws CVSException if there was a problem adding folder sync info.
	 */
	public FolderSyncInfo getFolderSync(IContainer folder) throws CVSException {
		if(folder.getType()==IResource.ROOT) return null;
		beginOperation();		
		FolderSyncInfo info = getCachedFolderSync(folder);
		if (info == null) {
			info = readFolderConfig(folder);
			setCachedFolderSync(folder, info);
		}
		endOperation();
		return info;
	}	

	/**
	 * Associates the provided sync information with the given file or folder. The resource
	 * may or may not exist on the file system however the parent folder must be a cvs
	 * folder.
	 * <p>
	 * The workbench and team plugins are notified that the state of this resources has 
	 * changed.</p>
	 * 
	 * @param file the file or folder for which to associate the sync info.
	 * @param info to set. The name in the resource info must match the file or folder name.
	 * 
 	 * @throws CVSException if there was a problem adding sync info.
	 */
	public void setResourceSync(IResource resource, ResourceSyncInfo info) throws CVSException {
		beginOperation();
		setCachedResourceSync(resource, info);
		changedResources.add(resource);		
		endOperation();
	}
	
	/**
	 * Answers the sync information associated with this file of folder or <code>null</code>
	 * if none is available. A resource cannot have sync information if its parent folder
	 * does not exist.
	 * 
	 * @param file the file or folder for which to return sync info.
 	 * @throws CVSException if there was a problem adding sync info or broadcasting
	 * the changes.
	 */
	public ResourceSyncInfo getResourceSync(IResource resource) throws CVSException {
		if(resource.getType()==IResource.ROOT) return null;
		beginOperation();
		ResourceSyncInfo info = getCachedResourceSync(resource);
		if (info == null) {
			IContainer parent = resource.getParent();
			if (parent != null) {
				ResourceSyncInfo[] infos = readEntriesFile(resource.getParent());
				if (infos != null) {
					for (int i = 0; i < infos.length; i++) {
						ResourceSyncInfo syncInfo = infos[i];
						IResource peer;
						if (resource.getName().equals(syncInfo.getName())) {
							info = syncInfo;
							peer = resource;
						} else {
							IPath path = new Path(syncInfo.getName());
							if (syncInfo.isDirectory()) {
								peer = parent.getFolder(path);
							} else {
								peer = parent.getFile(path);
							}
						}
						// may create a phantom if the sibling resource does not exist.
						setCachedResourceSync(peer, syncInfo);
					}
				}
			}
		}
		endOperation();
		return info;
	}
	
	/**
	 * Removes the folder's and all children's folder sync information. This will essentially remove
	 * all CVS knowledge from these resources.
	 */		
	public void deleteFolderSync(IContainer folder, IProgressMonitor monitor) throws CVSException {
		beginOperation();
		setCachedFolderSync(folder, null);
		changedFolders.add(folder);
		endOperation();
	}
	
	/**
	 * Removes the resource's sync information.
	 */
	public void deleteResourceSync(IResource resource, IProgressMonitor monitor) throws CVSException {
		beginOperation();
		setCachedResourceSync(resource, null);
		changedResources.add(resource);
		endOperation();
	}

	/**
	 * Answers if the following resource is ignored
	 */
	public boolean isIgnored(IResource resource) {
		// FIX ME!
		return false;
	}
	
	/**
	 * Adds a pattern or file name to be ignored in the current files directory.
	 */
	public void setIgnored(IResource resource, String pattern) throws CVSException {
		// FIX ME!
	}
	
	/**
	 * Answers an array with the sync information for immediate child resources of this folder. Note 
	 * that the returned sync information may be for resources that no longer exist (e.g. in the
	 * case of a pending deletion).
	 * 
	 * @param folder the folder for which to return the children resource sync infos. The folder
	 * must exist.
	 * 
	 * @throws CVSException if an error occurs retrieving the sync info.
	 */
	public IResource[] members(IContainer folder) throws CVSException {
		try {
			IResource[] children = folder.members(true);
			List list = new ArrayList(children.length);
			for (int i = 0; i < children.length; ++i) {
				IResource child = children[i];
				if (! child.isPhantom() || getCachedResourceSync(child) != null) {
					list.add(child);
				}
			}
			return (IResource[]) list.toArray(new IResource[list.size()]);
		} catch (CoreException e) {
			throw CVSException.wrapException(e);
		}
	}
	
	public void beginOperation() throws CVSException {
		if (nestingCount++ == 0) {
			// any work here?
		}
	}
	
	public void endOperation() throws CVSException {
		if (--nestingCount == 0) {
			if (! changedFolders.isEmpty() || ! changedResources.isEmpty()) {
				// write sync info to disk
				Iterator it = changedFolders.iterator();
				while (it.hasNext()) {
					IContainer folder = (IContainer) it.next();
					FolderSyncInfo info = getCachedFolderSync(folder);
					if (info != null) {
						writeFolderConfig(folder, info);
					} else {
						deleteFolderConfig(folder);
					}
				}
				it = changedResources.iterator();
				while (it.hasNext()) {
					IResource resource = (IResource) it.next();
					ResourceSyncInfo info = getCachedResourceSync(resource);
					if (info != null) {
						writeResourceSync(resource, info);
					} else {
						deleteResourceSync(resource);
					}
				}
				
				// broadcast events
				changedResources.addAll(changedFolders);
				changedFolders.clear();
				IResource[] resources = (IResource[]) changedResources.toArray(
					new IResource[changedResources.size()]);
				TeamPlugin.getManager().broadcastResourceStateChanges(resources);
				changedResources.clear();
			}
		}
	}
	
	private ISynchronizer getSynchronizer() {
		return ResourcesPlugin.getWorkspace().getSynchronizer();
	}
	
	private ResourceSyncInfo getCachedResourceSync(IResource resource) throws CVSException {
		try {
			byte[] bytes = getSynchronizer().getSyncInfo(RESOURCE_SYNC_KEY, resource);
			if(bytes==null) {
				return null;
			}
			return new ResourceSyncInfo(new String(bytes), null, null);
		} catch(CoreException e) {
			throw CVSException.wrapException(e);
		}
	}
	
	private void setCachedResourceSync(IResource resource, ResourceSyncInfo info) throws CVSException {
		try {
			if(info==null) {
				getSynchronizer().flushSyncInfo(RESOURCE_SYNC_KEY, resource, IResource.DEPTH_ZERO);
			} else {
				getSynchronizer().setSyncInfo(RESOURCE_SYNC_KEY, resource, info.getEntryLine(true).getBytes());
			}
		} catch(CoreException e) {
			throw CVSException.wrapException(e);
		}
	}
	
	private FolderSyncInfo getCachedFolderSync(IContainer folder) throws CVSException {
		try {
			byte[] bytes = getSynchronizer().getSyncInfo(FOLDER_SYNC_KEY, folder);
			if(bytes==null) {
				return null;
			}
			DataInputStream is = new DataInputStream(new ByteArrayInputStream(bytes));
			String repo = is.readUTF();
			String root = is.readUTF();
			String tag = is.readUTF();
			CVSTag cvsTag = null;
			boolean isStatic = is.readBoolean();
			if(!tag.equals("null")) {
				cvsTag = new CVSEntryLineTag(tag);
			}
			return new FolderSyncInfo(repo, root, cvsTag, isStatic);
		} catch (CoreException e) {
			throw CVSException.wrapException(e);
		} catch(IOException e) {
			throw CVSException.wrapException(e);
		}	
	}
	
	private void setCachedFolderSync(IContainer folder, FolderSyncInfo info) throws CVSException {
		try {
			if(info==null) {
				getSynchronizer().flushSyncInfo(FOLDER_SYNC_KEY, folder, IResource.DEPTH_INFINITE);
			} else {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				DataOutputStream os = new DataOutputStream(bos);
				os.writeUTF(info.getRepository());
				os.writeUTF(info.getRoot());
				CVSEntryLineTag tag = info.getTag();
				if(tag==null) {
					os.writeUTF("null");
				} else {
					os.writeUTF(info.getTag().toEntryLineFormat(false));
				}
				os.writeBoolean(info.getIsStatic());				
				getSynchronizer().setSyncInfo(FOLDER_SYNC_KEY, folder, bos.toByteArray());
				os.close();
			}
		} catch (CoreException e) {
			throw CVSException.wrapException(e);
		} catch(IOException e) {
			throw CVSException.wrapException(e);
		}
	}
	
	private FolderSyncInfo readFolderConfig(IContainer folder) throws CVSException {
		//return SyncFileUtil.readFolderConfig(folder.getLocation().toFile());
		return null;
	}
	
	private void writeFolderConfig(IContainer folder, FolderSyncInfo info) throws CVSException {
		/*
		SyncFileUtil.writeFolderConfig(folder.getLocation().toFile(), info);
		try { 
			folder.refreshLocal(IResource.DEPTH_INFINITE, null);
		} catch (CoreException e) {
			throw CVSException.wrapException(e);
		}
		*/
	}
	
	private void deleteFolderConfig(IContainer folder) throws CVSException {
		//SyncFileUtil.deleteSync(folder.getLocation().toFile());
	}
	
	private ResourceSyncInfo[] readEntriesFile(IContainer folder) throws CVSException {
		//return SyncFileUtil.readEntriesFile(folder.getLocation().toFile());
		return null;
	}
	
	private void writeResourceSync(IResource resource, ResourceSyncInfo info) throws CVSException {
		/*
		SyncFileUtil.writeResourceSync(resource.getLocation().toFile(), info);
		try { 
			resource.getParent().refreshLocal(IResource.DEPTH_INFINITE, null);
		} catch (CoreException e) {
			throw CVSException.wrapException(e);
		}*/
	}
	
	private void deleteResourceSync(IResource resource) throws CVSException {
		/*
		SyncFileUtil.deleteSync(resource.getLocation().toFile());
		try { 
			resource.getParent().refreshLocal(IResource.DEPTH_INFINITE, null);
		} catch (CoreException e) {
			throw CVSException.wrapException(e);
		}
		*/
	}
	
	public static EclipseSynchronizer getInstance() {
		if (instance == null) {
			instance = new EclipseSynchronizer();			
		}
		return instance;
	}
}