/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.core;

import java.io.*;
import java.util.*;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.ILock;
import org.eclipse.team.core.*;
import org.eclipse.team.internal.core.registry.DeploymentProviderDescriptor;
import org.eclipse.team.internal.core.registry.DeploymentProviderRegistry;

public class DeploymentProviderManager implements IDeploymentProviderManager  {
	
	// key for remembering if state has been loaded for a project
	private static final QualifiedName WRITE_TIMESTAMP = new QualifiedName(TeamPlugin.ID, "writetime"); //$NON-NLS-1$
	private final static QualifiedName STATE_LOADED_KEY = new QualifiedName("org.eclipse.team.core.deployment", "state_restored_key");
	
	// {project -> list of Mapping}
	private Map mappings = new HashMap(5);

	// registry for deployment provider extensions
	private DeploymentProviderRegistry registry;
	
	// lock to ensure that map/unmap and getProvider support concurrency
	private static final ILock mappingLock = Platform.getJobManager().newLock();
	
	//	persistence constants
	private final static String CTX_PROVIDERS = "deploymentProviders"; //$NON-NLS-1$
	private final static String CTX_PROVIDER = "provider"; //$NON-NLS-1$
	private final static String CTX_ID = "id"; //$NON-NLS-1$
	private final static String CTX_PATH = "container_path"; //$NON-NLS-1$
	private final static String CTX_PROVIDER_DATA = "data"; //$NON-NLS-1$
	private final static String FILENAME = ".deployments"; //$NON-NLS-1$
	
	static class Mapping {
		private DeploymentProviderDescriptor descriptor;
		private DeploymentProvider provider;
		private IContainer container;
		private IMemento savedState;
		
		Mapping(DeploymentProviderDescriptor descriptor, IContainer container) {
			this.descriptor = descriptor;
			this.container = container;
		}
		public DeploymentProvider getProvider() throws TeamException {
			if(provider == null) {
				try {
					this.provider = descriptor.createProvider();
					this.provider.setContainer(container);
					this.provider.restoreState(savedState);
					this.savedState = null;
				} catch (CoreException e) {
					throw TeamException.asTeamException(e);
				}				
			}
			return provider;
		}
		public void setProvider(DeploymentProvider provider) {
			this.provider = provider;
			this.savedState = null;
		}
		public IContainer getContainer() {
			return container;
		}
		public DeploymentProviderDescriptor getDescription() {
			return descriptor;
		}
		public void setProviderState(IMemento savedState) {
			this.savedState = savedState;
		}
	}
	
	public DeploymentProviderManager() {
		registry = new DeploymentProviderRegistry();
	}
	
	public void map(IContainer container, DeploymentProvider deploymentProvider) throws TeamException {
		try {
			mappingLock.acquire();
			if (!deploymentProvider.isMultipleMappingsSupported()) {
				// don't allow is overlapping deployment providers of the same type
				checkOverlapping(container, deploymentProvider.getID());
			}
			
			// extension point descriptor must exist
			DeploymentProviderDescriptor descriptor = registry.find(deploymentProvider.getID());		
			if(descriptor == null) {
				throw new TeamException("Cannot map provider " + deploymentProvider.getID() + ". It's extension point description cannot be found.");
			}
			
			// create the new mapping
			Mapping m = internalMap(container, descriptor);
			m.setProvider(deploymentProvider);
			deploymentProvider.setContainer(container);
			deploymentProvider.init();
			
			saveState(container.getProject());
			// TODO: what kind of event is generated when one is mapped?	
		} finally {
			mappingLock.release();
		}
	}
	
	public void unmap(IContainer container, DeploymentProvider teamProvider) throws TeamException {
		try {
			mappingLock.acquire();
			IProject project = container.getProject();
			List projectMaps = internalGetMappings(container);
			Mapping[] m = internalGetMappingsFor(container, teamProvider.getID());
			for (int i = 0; i < m.length; i++) {
				Mapping mapping = m[i];
				if (mapping.getProvider() == teamProvider) {
					projectMaps.remove(mapping);
					if(projectMaps.isEmpty()) {
						mappings.remove(project);
					}
				}
			}
			
			// dispose of provider
			teamProvider.dispose();
			saveState(container.getProject());
			
			// TODO: what kind of event is sent when unmapped?
		} finally {
			mappingLock.release();
		}
	}
	
	public DeploymentProvider[] getMappings(IResource resource) {
		List projectMappings = internalGetMappings(resource);
		String fullPath = resource.getFullPath().toString();
		List result = new ArrayList();
		if(projectMappings != null) {
			for (Iterator it = projectMappings.iterator(); it.hasNext();) {
				Mapping m = (Mapping) it.next();
				if(fullPath.startsWith(m.getContainer().getFullPath().toString())) {
					try {
						// lazy initialize of provider must be supported
						// TODO: It is possible that the provider has been unmap concurrently
						result.add(m.getProvider());
					} catch (CoreException e) {
						TeamPlugin.log(e);
					}
				}
			}
		}
		return (DeploymentProvider[]) result.toArray(new DeploymentProvider[result.size()]);
	}
	
	public DeploymentProvider[] getMappings(IResource resource, String id) {
		Mapping[] m = internalGetMappingsFor(resource, id);
		List result = new ArrayList();
		for (int i = 0; i < m.length; i++) {
			Mapping mapping = m[i];
			try {
				// lazy initialize of provider must be supported
				// TODO: It is possible that the provider has been unmap concurrently
				result.add(mapping.getProvider());
			} catch (TeamException e) {
				TeamPlugin.log(e);
			}
		}
		
		DeploymentProvider[] providers = (DeploymentProvider[]) result.toArray(new DeploymentProvider[result.size()]);
		// Ensure that multiple providers are not mapped if it is not supported 
		// by the provider type. This could occur if the deployment configuration 
		// was loaded from a repository or modified manually
		if (providers.length > 1 && !providers[0].isMultipleMappingsSupported()) {
			// Log and ignore all but one of the mappings
			TeamPlugin.log(IStatus.WARNING, "Resource {0} is mapped to multiple deployment providers of type {1}." +resource +id, null);
			return new DeploymentProvider[] { providers[0] };
		}
		return providers;
	}
	
	public boolean getMappedTo(IResource resource, String id) {
		return internalGetMappingsFor(resource, id).length > 0;
	}
	
	private void checkOverlapping(IContainer container, String id) throws TeamException {
		List projectMappings = internalGetMappings(container);
		String fullPath = container.getFullPath().toString();
		if(projectMappings != null) {
			for (Iterator it = projectMappings.iterator(); it.hasNext();) {
				Mapping m = (Mapping) it.next();
				String first = m.getContainer().getFullPath().toString();
				if(fullPath.startsWith(first) || first.startsWith(fullPath)) {
					if (m.getDescription().getId().equals(id)) {
						throw new TeamException(container.getFullPath().toString() + " is already mapped to " + m.getDescription().getId());
					}
				}
			}
		}
	}
	
	private Mapping internalMap(IContainer container, DeploymentProviderDescriptor description) {
		Mapping newMapping = new Mapping(description, container);
		return internalMap(container, newMapping);
	}
	
	private Mapping internalMap(IContainer container, Mapping newMapping) {
		IProject project = container.getProject();
		List projectMaps = (List)mappings.get(project);
		if(projectMaps == null) {
			projectMaps = new ArrayList();
			mappings.put(project, projectMaps);
		}
		projectMaps.add(newMapping);
		return newMapping;
	}

	/*
	 * Loads all the mappings associated with the resource's project.
	 */
	private List internalGetMappings(IResource resource) {
		try {
			mappingLock.acquire();
			IProject project = resource.getProject();
			List m = (List)mappings.get(project);
			try {
				if(project.getSessionProperty(STATE_LOADED_KEY) != null) {
					return m;
				}
				Mapping[] projectMappings = loadMappings(project);
				for (int i = 0; i < projectMappings.length; i++) {
					Mapping mapping = projectMappings[i];
					internalMap(mapping.getContainer(), mapping);
				}
				
				project.setSessionProperty(STATE_LOADED_KEY, "true");
			} catch (TeamException e) {
			} catch (CoreException e) {
			}		
			return (List)mappings.get(project);
		} finally {
			mappingLock.release();
		}
	}
	
	private Mapping[] internalGetMappingsFor(IResource resource, String id) {
		List projectMappings = internalGetMappings(resource);
		List result = new ArrayList();
		String fullPath = resource.getFullPath().toString();
		if(projectMappings != null) {
			for (Iterator it = projectMappings.iterator(); it.hasNext();) {
				Mapping m = (Mapping) it.next();				
				// mapping can be initialize without having provider loaded yet!
				if(m.getDescription().getId().equals(id) && fullPath.startsWith(m.getContainer().getFullPath().toString())) {
					result.add(m);
				}
			}
		}
		return (Mapping[]) result.toArray(new Mapping[result.size()]);
	}
	
	/**
	 * Saves a file containing the list of participant ids that are registered with this
	 * manager. Each participant is also given the chance to save it's state. 
	 */
	private void saveState(IProject project) throws TeamException {
		IFile settingsFile = project.getFile(FILENAME);
		try {
			// Obtain a rule to ensure that others don't modify the file
			Platform.getJobManager().beginRule(settingsFile, null);
			XMLMemento xmlMemento = XMLMemento.createWriteRoot(CTX_PROVIDERS);	
			List providers = (List)mappings.get(project);
			if(providers == null) {
				if(settingsFile.exists()) {
					settingsFile.delete(true /* force */, true /* keep history */, null);
				}
			} else {
				for (Iterator it2 = providers.iterator(); it2.hasNext(); ) {
					Mapping mapping = (Mapping) it2.next();
					IMemento node = xmlMemento.createChild(CTX_PROVIDER);
					node.putString(CTX_ID, mapping.getDescription().getId());
					node.putString(CTX_PATH, mapping.getContainer().getProjectRelativePath().toString());
					mapping.getProvider().saveState(node.createChild(CTX_PROVIDER_DATA));
				}
				if(! settingsFile.exists()) {
					settingsFile.create(new ByteArrayInputStream(new byte[0]), true, null);
				}
				Writer writer = new BufferedWriter(new FileWriter(settingsFile.getLocation().toFile()));
				try {
					xmlMemento.save(writer);
				} finally {
					writer.close();
					settingsFile.refreshLocal(IResource.DEPTH_ZERO, null);
					settingsFile.setPersistentProperty(WRITE_TIMESTAMP, Long.toString(settingsFile.getModificationStamp()));
				}
				// Write the state to the meta-data area as well to ensure that
				// external modifications made before the state is loaded do not
				// result in leaked resources
				IPath metaPath = project.getPluginWorkingLocation(TeamPlugin.getPlugin().getDescriptor());
				metaPath = metaPath.append(FILENAME);
				writer = new BufferedWriter(new FileWriter(metaPath.toFile()));
				try {
					xmlMemento.save(writer);
				} finally {
					writer.close();
				}
			}
		} catch (IOException e) {
			throw new TeamException("An I/O error occurred while persisting the deployment configurations for project {0}." + project.getName(), e);
		} catch(CoreException ce) {
			throw TeamException.asTeamException(ce);
		} finally {
			Platform.getJobManager().endRule(settingsFile);
		}
	}
	
	/*
	 * Load the mappings for the given project and return them.
	 */
	private Mapping[] loadMappings(IProject project) throws TeamException, CoreException {
		IFile file = project.getFile(FILENAME);
		if(! file.exists()) {
			// The file may have been deleted before our delta listener was loaded.
			// If there are any deployments stored in the meta data area, dispose of them
			// TODO: See if there were any before and dispose of them
			return new Mapping[0];
		}
		String timestamp = file.getPersistentProperty(WRITE_TIMESTAMP);
		try {
			if (timestamp == null || Long.getLong(timestamp).longValue() != file.getModificationStamp()) {
				// The file has been modified externally
				// TODO:
			}
		} catch (NumberFormatException e1) {
			// The write timestamp was corrupt.
			// TODO: 
		}
		Reader reader;
		try {
			reader = new BufferedReader(new FileReader(file.getLocation().toFile()));
		} catch (FileNotFoundException e) {
			return new Mapping[0];
		}
		return loadMappings(project, reader);
	}

	private Mapping[] loadMappings(IProject project, Reader reader) throws TeamException {
		IMemento memento = XMLMemento.createReadRoot(reader);
		IMemento[] providers = memento.getChildren(CTX_PROVIDER);
		List projectMappings = new ArrayList();
		for (int i = 0; i < providers.length; i++) {
			IMemento memento2 = providers[i];			
			String id = memento2.getString(CTX_ID);
			IPath location = new Path(memento2.getString(CTX_PATH));
			
			if(! project.exists(location)) {
				TeamPlugin.log(IStatus.ERROR, "Previously deployed folder {0} in project {1} no longer exists." + location + project.getName(), null);
			}
			IResource resource = location.isEmpty() ? (IContainer)project : project.findMember(location);
			if (resource.getType() == IResource.FILE) {
				TeamPlugin.log(IStatus.ERROR, "Previously deployed resource {0} in project {1} is now a file and cannot be deployed." + location + project.getName(), null);
			}
			IContainer container = (IContainer)resource;
			DeploymentProviderDescriptor desc = registry.find(id);				
			if(desc != null) {
				Mapping m = new Mapping(desc, container);
				m.setProviderState(memento2.getChild(CTX_PROVIDER_DATA));
				projectMappings.add(m);
			} else {
				TeamPlugin.log(IStatus.ERROR, Policy.bind("SynchronizeManager.9", id), null); //$NON-NLS-1$
			}
		}
		return (Mapping[]) projectMappings.toArray(new Mapping[projectMappings.size()]);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.team.core.IDeploymentProviderManager#getDeploymentProviderRoots(java.lang.String)
	 */
	public IResource[] getDeploymentProviderRoots(String id) {
		IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
		Set roots = new HashSet();
		for (int i = 0; i < projects.length; i++) {
			IProject project = projects[i];
			List mappings = internalGetMappings(project);
			if (mappings != null) {
				for (Iterator iter = mappings.iterator(); iter.hasNext();) {
					Mapping mapping = (Mapping) iter.next();
					if (id == null || mapping.getDescription().getId().equals(id)) {
						roots.add(mapping.getContainer());
					}
				}
			}
		}
		return (IResource[]) roots.toArray(new IResource[roots.size()]);
	}

}