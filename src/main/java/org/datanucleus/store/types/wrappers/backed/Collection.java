/**********************************************************************
Copyright (c) 2003 Mike Martin and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.


Contributors:
2003 Andy Jefferson - rewritten to always have delegate present
2004 Andy Jefferson - rewritten to allow delegate caching
2005 Andy Jefferson - allowed for serialised collection
2005 Andy Jefferson - added support for Collections implemented as List
    ...
**********************************************************************/
package org.datanucleus.store.types.wrappers.backed;

import java.io.ObjectStreamException;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.PersistableObjectType;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.flush.CollectionAddOperation;
import org.datanucleus.flush.CollectionClearOperation;
import org.datanucleus.flush.CollectionRemoveOperation;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.FieldPersistenceModifier;
import org.datanucleus.metadata.RelationType;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.state.RelationshipManager;
import org.datanucleus.store.BackedSCOStoreManager;
import org.datanucleus.store.types.SCOCollection;
import org.datanucleus.store.types.SCOCollectionIterator;
import org.datanucleus.store.types.SCOUtils;
import org.datanucleus.store.types.scostore.CollectionStore;
import org.datanucleus.store.types.scostore.Store;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

/** 
 * A mutable second-class Collection object.
 * This class extends Collection, using that class to contain the current objects, and the backing CollectionStore 
 * to be the interface to the datastore. A "backing store" is not present for datastores that dont use
 * DatastoreClass, or if the container is serialised or non-persistent.
 * 
 * <H3>Modes of Operation</H3>
 * The user can operate the list in 2 modes.
 * The <B>cached</B> mode will use an internal cache of the elements (in the "delegate") reading them at
 * the first opportunity and then using the cache thereafter.
 * The <B>non-cached</B> mode will just go direct to the "backing store" each call.
 *
 * <H3>Mutators</H3>
 * When the "backing store" is present any updates are passed direct to the datastore as well as to the "delegate".
 * If the "backing store" isn't present the changes are made to the "delegate" only.
 *
 * <H3>Accessors</H3>
 * When any accessor method is invoked, it typically checks whether the container has been loaded from its
 * "backing store" (where present) and does this as necessary. Some methods (<B>size()</B>) just check if 
 * everything is loaded and use the delegate if possible, otherwise going direct to the datastore.
 */
public class Collection<E> extends org.datanucleus.store.types.wrappers.Collection<E> implements BackedSCO
{
    protected transient CollectionStore<E> backingStore;
    protected transient boolean allowNulls = false;
    protected transient boolean useCache = true;
    protected transient boolean isCacheLoaded = false;
    protected transient boolean initialising = false;

    /**
     * Constructor.
     * @param sm StateManager for this collection.
     * @param mmd Metadata for the member
     */
    public Collection(DNStateManager sm, AbstractMemberMetaData mmd)
    {
        super(sm, mmd);

        allowNulls = SCOUtils.allowNullsInContainer(allowNulls, mmd);
        useCache = SCOUtils.useContainerCache(sm, mmd);

        if (!SCOUtils.collectionHasSerialisedElements(mmd) && mmd.getPersistenceModifier() == FieldPersistenceModifier.PERSISTENT)
        {
            ClassLoaderResolver clr = sm.getExecutionContext().getClassLoaderResolver();
            this.backingStore = (CollectionStore)((BackedSCOStoreManager)sm.getStoreManager()).getBackingStoreForField(clr, mmd, java.util.Collection.class);
        }

        // Set up our delegate
        if (this.backingStore != null && this.backingStore.hasOrderMapping())
        {
            // Use an ArrayList since we need ordering, duplicates etc
            this.delegate = new java.util.ArrayList();
        }
        else
        {
            // Use a HashSet since we don't need ordering, duplicates
            this.delegate = new java.util.HashSet();
        }

        if (NucleusLogger.PERSISTENCE.isDebugEnabled())
        {
            NucleusLogger.PERSISTENCE.debug(SCOUtils.getContainerInfoMessage(sm, ownerMmd.getName(), this,
                useCache, allowNulls, SCOUtils.useCachedLazyLoading(sm, ownerMmd)));
        }
    }

    /**
     * Constructor used when creating a Collection for "Map.values" with specified backing store.
     * @param ownerSM StateManager for the owning object
     * @param mmd Metadata for the member
     * @param allowNulls Whether nulls are allowed
     * @param backingStore The backing store
     */
    public Collection(DNStateManager ownerSM, AbstractMemberMetaData mmd, boolean allowNulls, CollectionStore backingStore)
    {
        super(ownerSM, mmd);

        this.allowNulls = allowNulls;

        // Set up our delegate
        this.delegate = new java.util.HashSet();

        ExecutionContext ec = ownerSM.getExecutionContext();
        allowNulls = SCOUtils.allowNullsInContainer(allowNulls, mmd);
        useCache = SCOUtils.useContainerCache(ownerSM, mmd);

        ClassLoaderResolver clr = ec.getClassLoaderResolver();
        if (backingStore != null)
        {
            this.backingStore = backingStore;
        }
        else if (!SCOUtils.collectionHasSerialisedElements(mmd) && mmd.getPersistenceModifier() == FieldPersistenceModifier.PERSISTENT)
        {
            this.backingStore = (CollectionStore)
                ((BackedSCOStoreManager)ec.getStoreManager()).getBackingStoreForField(clr, mmd, java.util.Collection.class);
        }
        if (NucleusLogger.PERSISTENCE.isDebugEnabled())
        {
            NucleusLogger.PERSISTENCE.debug(SCOUtils.getContainerInfoMessage(ownerSM, ownerMmd.getName(), this,
                useCache, allowNulls, SCOUtils.useCachedLazyLoading(ownerSM, ownerMmd)));
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.types.wrappers.Collection#initialise(java.util.Collection, java.util.Collection)
     */
    @Override
    public void initialise(java.util.Collection<E> newValue, Object oldValue)
    {
        if (newValue instanceof java.util.List && !(delegate instanceof java.util.List))
        {
            // Need to set the value to a List so we change our delegate to match
            delegate = new java.util.ArrayList();
        }

        if (newValue != null)
        {
            // Check for the case of serialised PC elements, and assign StateManagers to the elements without
            if (SCOUtils.collectionHasSerialisedElements(ownerMmd) && ownerMmd.getCollection().elementIsPersistent())
            {
                ExecutionContext ec = ownerSM.getExecutionContext();
                Iterator iter = newValue.iterator();
                while (iter.hasNext())
                {
                    Object pc = iter.next();
                    DNStateManager sm = ec.findStateManager(pc);
                    if (sm == null)
                    {
                        sm = ec.getNucleusContext().getStateManagerFactory().newForEmbedded(ec, pc, false, ownerSM, ownerMmd.getAbsoluteFieldNumber(),
                            PersistableObjectType.EMBEDDED_COLLECTION_ELEMENT_PC);
                    }
                }
            }

            if (NucleusLogger.PERSISTENCE.isDebugEnabled())
            {
                NucleusLogger.PERSISTENCE.debug(Localiser.msg("023008", ownerSM.getObjectAsPrintable(), ownerMmd.getName(), "" + newValue.size()));
            }

            if (delegate instanceof Set)
            {
                // Detect which objects are added and which are deleted
                initialising = true;
                if (useCache)
                {
                    java.util.Collection oldColl = (java.util.Collection)oldValue;
                    if (oldColl != null)
                    {
                        delegate.addAll(oldColl);
                    }
                    isCacheLoaded = true;

                    SCOUtils.updateCollectionWithCollection(ownerSM.getExecutionContext().getApiAdapter(), this, newValue);
                }
                else
                {
                    java.util.Collection oldColl = (java.util.Collection)oldValue;
                    if (oldColl instanceof SCOCollection)
                    {
                        oldColl = (java.util.Collection) ((SCOCollection)oldColl).getValue();
                    }

                    for (E elem : newValue)
                    {
                        if (oldColl == null || !oldColl.contains(elem))
                        {
                            add(elem);
                        }
                    }
                    if (oldColl != null)
                    {
                        Iterator iter = oldColl.iterator();
                        while (iter.hasNext())
                        {
                            Object elem = iter.next();
                            if (!newValue.contains(elem))
                            {
                                remove(elem);
                            }
                        }
                    }
                }
                initialising = false;
            }
            else
            {
                // TODO This does clear+addAll : Improve this and work out which elements are added and which deleted
                if (backingStore != null)
                {
                    if (SCOUtils.useQueuedUpdate(ownerSM))
                    {
                        if (ownerSM.isFlushedToDatastore() || !ownerSM.getLifecycleState().isNew())
                        {
                            ownerSM.getExecutionContext().addOperationToQueue(new CollectionClearOperation(ownerSM, backingStore));

                            for (Object element : newValue)
                            {
                                ownerSM.getExecutionContext().addOperationToQueue(new CollectionAddOperation(ownerSM, backingStore, element));
                            }
                        }
                    }
                    else
                    {
                        backingStore.clear(ownerSM);

                        try
                        {
                            backingStore.addAll(ownerSM, newValue, useCache ? 0 : -1);
                        }
                        catch (NucleusDataStoreException dse)
                        {
                            NucleusLogger.PERSISTENCE.warn(Localiser.msg("023013", "addAll", ownerMmd.getName(), dse));
                        }
                    }
                }
                delegate.addAll(newValue);
                isCacheLoaded = true;
                makeDirty();
            }
        }
    }

    /**
     * Method to initialise the SCO from an existing value.
     * @param c The object to set from
     */
    public void initialise(java.util.Collection c)
    {
        if (c instanceof java.util.List && !(delegate instanceof java.util.List))
        {
            // Need to set the value to a List so we change our delegate to match
            delegate = new java.util.ArrayList();
        }

        if (c != null)
        {
            // Check for the case of serialised PC elements, and assign StateManagers to the elements without
            if (SCOUtils.collectionHasSerialisedElements(ownerMmd) && ownerMmd.getCollection().elementIsPersistent())
            {
                ExecutionContext ec = ownerSM.getExecutionContext();
                Iterator iter = c.iterator();
                while (iter.hasNext())
                {
                    Object pc = iter.next();
                    DNStateManager sm = ec.findStateManager(pc);
                    if (sm == null)
                    {
                        sm = ec.getNucleusContext().getStateManagerFactory().newForEmbedded(ec, pc, false, ownerSM, ownerMmd.getAbsoluteFieldNumber(),
                            PersistableObjectType.EMBEDDED_COLLECTION_ELEMENT_PC);
                    }
                }
            }

            if (backingStore != null && useCache && !isCacheLoaded)
            {
                // Mark as loaded
                isCacheLoaded = true;
            }

            if (NucleusLogger.PERSISTENCE.isDebugEnabled())
            {
                NucleusLogger.PERSISTENCE.debug(Localiser.msg("023007", ownerSM.getObjectAsPrintable(), ownerMmd.getName(), "" + c.size()));
            }
            delegate.clear();
            delegate.addAll(c);
        }
    }

    /**
     * Method to initialise the SCO for use.
     */
    public void initialise()
    {
        if (useCache && !SCOUtils.useCachedLazyLoading(ownerSM, ownerMmd))
        {
            // Load up the collection now if not using lazy loading
            loadFromStore();
        }
    }

    // ----------------------- Implementation of SCO methods -------------------

    /**
     * Accessor for the unwrapped value that we are wrapping.
     * @return The unwrapped value
     */
    public java.util.Collection getValue()
    {
        loadFromStore();
        return super.getValue();
    }

    /**
     * Method to effect the load of the data in the SCO.
     * Used when the SCO supports lazy-loading to tell it to load all now.
     */
    public void load()
    {
        if (useCache)
        {
            loadFromStore();
        }
    }

    /**
     * Method to return if the SCO has its contents loaded.
     * If the SCO doesn't support lazy loading will just return true.
     * @return Whether it is loaded
     */
    public boolean isLoaded()
    {
        return useCache ? isCacheLoaded : false;
    }

    /**
     * Method to load all elements from the "backing store" where appropriate.
     */
    protected void loadFromStore()
    {
        if (backingStore != null && !isCacheLoaded)
        {
            if (NucleusLogger.PERSISTENCE.isDebugEnabled())
            {
                NucleusLogger.PERSISTENCE.debug(Localiser.msg("023006", ownerSM.getObjectAsPrintable(), ownerMmd.getName()));
            }
            delegate.clear();

            ExecutionContext ec = ownerSM.getExecutionContext();
            RelationType relType = ownerMmd.getRelationType(ec.getClassLoaderResolver());
            int relatedMemberNum = -1;
            if (RelationType.isBidirectional(relType) && relType == RelationType.ONE_TO_MANY_BI)
            {
                AbstractMemberMetaData[] relMmds = ownerMmd.getRelatedMemberMetaData(ec.getClassLoaderResolver());
                relatedMemberNum = (relMmds != null && relMmds.length > 0) ? relMmds[0].getAbsoluteFieldNumber() : -1;
            }

            Iterator<E> iter = backingStore.iterator(ownerSM);
            while (iter.hasNext())
            {
                E element = iter.next();
                if (relatedMemberNum >= 0)
                {
                    DNStateManager elemSM = ec.findStateManager(element);
                    if (!elemSM.isFieldLoaded(relatedMemberNum))
                    {
                        // Store the "id" value in case the container owner member is ever accessed
                        elemSM.storeFieldValue(relatedMemberNum, ownerSM.getExternalObjectId());
                    }
                }
                delegate.add(element);
            }

            isCacheLoaded = true;
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.types.backed.BackedSCO#getBackingStore()
     */
    public Store getBackingStore()
    {
        return backingStore;
    }

    /**
     * Method to update an embedded element in this collection.
     * @param element The element
     * @param fieldNumber Number of field in the element
     * @param value New value for this field
     * @param makeDirty Whether to make the SCO field dirty.
     */
    public void updateEmbeddedElement(E element, int fieldNumber, Object value, boolean makeDirty)
    {
        if (backingStore != null)
        {
            backingStore.updateEmbeddedElement(ownerSM, element, fieldNumber, value);
        }
    }

    /**
     * Method to unset the owner state manager and backing store information.
     */
    public void unsetOwner()
    {
        super.unsetOwner();
        if (backingStore != null)
        {
            backingStore = null;
        }
    }

    // ---------------- Implementation of Collection methods -------------------
 
    /**
     * Creates and returns a copy of this object.
     * <P>Mutable second-class Objects are required to provide a public clone method in order to allow for copying persistable objects.
     * In contrast to Object.clone(), this method must not throw a CloneNotSupportedException.
     * @return A clone of the object
     */
    public Object clone()
    {
        if (useCache)
        {
            loadFromStore();
        }

        if (delegate instanceof java.util.ArrayList)
        {
            return ((java.util.ArrayList)delegate).clone();
        }
        return ((java.util.HashSet)delegate).clone();
    }

    @Override
    public boolean contains(Object element)
    {
        if (useCache && isCacheLoaded)
        {
            // If the "delegate" is already loaded, use it
            return delegate.contains(element);
        }
        else if (backingStore != null)
        {
            return backingStore.contains(ownerSM,element);
        }

        return delegate.contains(element);
    }

    @Override
    public boolean containsAll(java.util.Collection c)
    {
        if (useCache)
        {
            loadFromStore();
        }
        else if (backingStore != null)
        {
            java.util.HashSet h=new java.util.HashSet(c);
            Iterator iter=iterator();
            while (iter.hasNext())
            {
                h.remove(iter.next());
            }

            return h.isEmpty();
        }

        return delegate.containsAll(c);
    }

    @Override
    public boolean equals(Object o)
    {
        if (useCache)
        {
            loadFromStore();
        }

        if (o == this)
        {
            return true;
        }
        if (!(o instanceof java.util.Collection))
        {
            return false;
        }
        java.util.Collection c=(java.util.Collection)o;

        return c.size() == size() && containsAll(c);
    }

    @Override
    public void forEach(Consumer action)
    {
        Objects.requireNonNull(action);
        for (E t : this)
        { // uses iterator() implicitly
            action.accept(t);
        }
    }

    @Override
    public int hashCode()
    {
        if (useCache)
        {
            loadFromStore();
        }
        return delegate.hashCode();
    }

    @Override
    public boolean isEmpty()
    {
        return size() == 0;
    }

    @Override
    public Iterator<E> iterator()
    {
        // Populate the cache if necessary
        if (useCache)
        {
            loadFromStore();
        }
        return new SCOCollectionIterator(this, ownerSM, delegate, backingStore, useCache);
    }

    @Override
    public int size()
    {
        if (useCache && isCacheLoaded)
        {
            // If the "delegate" is already loaded, use it
            return delegate.size();
        }
        else if (backingStore != null)
        {
            return backingStore.size(ownerSM);
        }

        return delegate.size();
    }

    @Override
    public Object[] toArray()
    {
        if (useCache)
        {
            loadFromStore();
        }
        else if (backingStore != null)
        {
            return SCOUtils.toArray(backingStore,ownerSM);
        }  
        return delegate.toArray();
    }

    @Override
    public Object[] toArray(Object a[])
    {
        if (useCache)
        {
            loadFromStore();
        }
        else if (backingStore != null)
        {
            return SCOUtils.toArray(backingStore,ownerSM,a);
        }  
        return delegate.toArray(a);
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder("[");
        int i=0;
        Iterator iter=iterator();
        while (iter.hasNext())
        {
            if (i > 0)
            {
                s.append(',');
            }
            s.append(iter.next());
            i++;
        }
        s.append("]");

        return s.toString();
    }

    @Override
    public boolean add(E element)
    {
        // Reject inappropriate elements
        if (!allowNulls && element == null)
        {
            throw new NullPointerException("Nulls not allowed for collection at field " + ownerMmd.getName() + " but element is null");
        }

        if (useCache)
        {
            loadFromStore();
        }
        if (!List.class.isAssignableFrom(delegate.getClass()) && contains(element))
        {
            return false;
        }

        if (ownerSM != null && ownerSM.getExecutionContext().getManageRelations() && !initialising)
        {
            // Relationship management
            ownerSM.getExecutionContext().getRelationshipManager(ownerSM).relationAdd(ownerMmd.getAbsoluteFieldNumber(), element);
        }

        boolean backingSuccess = true;
        if (backingStore != null)
        {
            if (SCOUtils.useQueuedUpdate(ownerSM))
            {
                ownerSM.getExecutionContext().addOperationToQueue(new CollectionAddOperation(ownerSM, backingStore, element));
            }
            else
            {
                try
                {
                    backingSuccess = backingStore.add(ownerSM, element, useCache ? delegate.size() : -1);
                }
                catch (NucleusDataStoreException dse)
                {
                    throw new IllegalArgumentException(Localiser.msg("023013", "add", ownerMmd.getName(), dse), dse);
                }
            }
        }

        // Only make it dirty after adding the element(s) to the datastore so we give it time
        // to be inserted - otherwise jdoPreStore on this object would have been called before completing the addition
        makeDirty();

        boolean delegateSuccess = delegate.add(element);

        if (ownerSM != null && !ownerSM.getExecutionContext().getTransaction().isActive())
        {
            ownerSM.getExecutionContext().processNontransactionalUpdate();
        }
        return backingStore != null ? backingSuccess : delegateSuccess;
    }

    @Override
    public boolean addAll(java.util.Collection elements)
    {
        if (useCache)
        {
            loadFromStore();
        }
        if (ownerSM != null && ownerSM.getExecutionContext().getManageRelations() && !initialising)
        {
            // Relationship management
            RelationshipManager relMgr = ownerSM.getExecutionContext().getRelationshipManager(ownerSM);
            for (Object elem : elements)
            {
                relMgr.relationAdd(ownerMmd.getAbsoluteFieldNumber(), elem);
            }
        }

        boolean backingSuccess = true;
        if (backingStore != null)
        {
            if (SCOUtils.useQueuedUpdate(ownerSM))
            {
                for (Object element : elements)
                {
                    ownerSM.getExecutionContext().addOperationToQueue(new CollectionAddOperation(ownerSM, backingStore, element));
                }
            }
            else
            {
                try
                {
                    backingSuccess = backingStore.addAll(ownerSM, elements, useCache ? delegate.size() : -1);
                }
                catch (NucleusDataStoreException dse)
                {
                    throw new IllegalArgumentException(Localiser.msg("023013", "addAll", ownerMmd.getName(), dse), dse);
                }
            }
        }

        // Only make it dirty after adding the element(s) to the datastore so we give it time
        // to be inserted - otherwise jdoPreStore on this object would have been called before completing the addition
        makeDirty();

        boolean delegateSuccess = delegate.addAll(elements);

        if (ownerSM != null && !ownerSM.getExecutionContext().getTransaction().isActive())
        {
            ownerSM.getExecutionContext().processNontransactionalUpdate();
        }
        return backingStore != null ? backingSuccess : delegateSuccess;
    }

    @Override
    public void clear()
    {
        if (ownerSM != null && ownerSM.getExecutionContext().getManageRelations() && !initialising)
        {
            // Relationship management
            RelationshipManager relMgr = ownerSM.getExecutionContext().getRelationshipManager(ownerSM);
            for (Object elem : delegate)
            {
                relMgr.relationRemove(ownerMmd.getAbsoluteFieldNumber(), elem);
            }
        }

        makeDirty();
        delegate.clear();

        if (backingStore != null)
        {
            if (SCOUtils.useQueuedUpdate(ownerSM))
            {
                ownerSM.getExecutionContext().addOperationToQueue(new CollectionClearOperation(ownerSM, backingStore));
            }
            else
            {
                backingStore.clear(ownerSM);
            }
        }

        if (ownerSM != null && !ownerSM.getExecutionContext().getTransaction().isActive())
        {
            ownerSM.getExecutionContext().processNontransactionalUpdate();
        }
    }

    @Override
    public boolean remove(Object element)
    {
        return remove(element, true);
    }

    @Override
    public boolean remove(Object element, boolean allowCascadeDelete)
    {
        makeDirty();

        if (useCache)
        {
            loadFromStore();
        }

        int size = useCache ? delegate.size() : -1;
        boolean contained = delegate.contains(element);
        if (useCache && !contained)
        {
            // Element not present in the delegate so nothing to do
            return false;
        }
        boolean delegateSuccess = delegate.remove(element);
        if (ownerSM != null && ownerSM.getExecutionContext().getManageRelations() && !initialising)
        {
            ownerSM.getExecutionContext().getRelationshipManager(ownerSM).relationRemove(ownerMmd.getAbsoluteFieldNumber(), element);
        }

        boolean backingSuccess = true;
        if (backingStore != null)
        {
            if (SCOUtils.useQueuedUpdate(ownerSM))
            {
                backingSuccess = contained;
                if (backingSuccess)
                {
                    ownerSM.getExecutionContext().addOperationToQueue(new CollectionRemoveOperation(ownerSM, backingStore, element, allowCascadeDelete));
                }
            }
            else
            {
                try
                {
                    backingSuccess = backingStore.remove(ownerSM, element, size, allowCascadeDelete);
                }
                catch (NucleusDataStoreException dse)
                {
                    NucleusLogger.PERSISTENCE.warn(Localiser.msg("023013", "remove", ownerMmd.getName(), dse));
                    backingSuccess = false;
                }
            }
        }

        if (ownerSM != null && !ownerSM.getExecutionContext().getTransaction().isActive())
        {
            ownerSM.getExecutionContext().processNontransactionalUpdate();
        }

        return backingStore != null ? backingSuccess : delegateSuccess;
    }

    @Override
    public boolean removeAll(java.util.Collection elements)
    {
        if (elements == null)
        {
            throw new NullPointerException();
        }
        else if (elements.isEmpty())
        {
            return true;
        }

        makeDirty();
 
        if (useCache)
        {
            loadFromStore();
        }

        int size = useCache ? delegate.size() : -1;
        boolean delegateSuccess = delegate.removeAll(elements);

        if (ownerSM != null && ownerSM.getExecutionContext().getManageRelations() && !initialising)
        {
            // Relationship management
            RelationshipManager relMgr = ownerSM.getExecutionContext().getRelationshipManager(ownerSM);
            for (Object elem : elements)
            {
                relMgr.relationRemove(ownerMmd.getAbsoluteFieldNumber(), elem);
            }
        }

        if (backingStore != null && ownerSM != null)
        {
            boolean backingSuccess = true;
            if (SCOUtils.useQueuedUpdate(ownerSM))
            {
                // Check which are contained before updating the delegate
                java.util.Collection contained = new java.util.HashSet();
                for (Object elem : elements)
                {
                    if (contains(elem))
                    {
                        contained.add(elem);
                    }
                }
                if (!contained.isEmpty())
                {
                    backingSuccess = false;
                    for (Object element : contained)
                    {
                        backingSuccess = true;
                        ownerSM.getExecutionContext().addOperationToQueue(new CollectionRemoveOperation(ownerSM, backingStore, element, true));
                    }
                }
            }
            else
            {
                try
                {
                    backingSuccess = backingStore.removeAll(ownerSM, elements, size);
                }
                catch (NucleusDataStoreException dse)
                {
                    NucleusLogger.PERSISTENCE.warn(Localiser.msg("023013", "removeAll", ownerMmd.getName(), dse));
                    backingSuccess = false;
                }
            }

            if (!ownerSM.getExecutionContext().getTransaction().isActive())
            {
                ownerSM.getExecutionContext().processNontransactionalUpdate();
            }

            return backingSuccess;
        }

        if (ownerSM != null && !ownerSM.getExecutionContext().getTransaction().isActive())
        {
            ownerSM.getExecutionContext().processNontransactionalUpdate();
        }
        return delegateSuccess;
    }

    @Override
    public boolean retainAll(java.util.Collection c)
    {
        makeDirty();

        if (useCache)
        {
            loadFromStore();
        }

        boolean modified = false;
        Iterator iter = iterator();
        while (iter.hasNext())
        {
            Object element = iter.next();
            if (!c.contains(element))
            {
                iter.remove();
                modified = true;
            }
        }

        if (ownerSM != null && !ownerSM.getExecutionContext().getTransaction().isActive())
        {
            ownerSM.getExecutionContext().processNontransactionalUpdate();
        }
        return modified;
    }

    @Override
    protected Object writeReplace() throws ObjectStreamException
    {
        if (useCache)
        {
            loadFromStore();
            return new java.util.HashSet(delegate);
        }

        // TODO Cater for non-cached collection, load elements in a DB call.
        return new java.util.HashSet(delegate);
    }

    @Override
    public Spliterator spliterator()
    {
        if (backingStore != null && useCache && !isCacheLoaded)
        {
            loadFromStore();
        }
        // TODO If using backing store yet not caching, then this will fail
        return delegate.spliterator();
    }

    @Override
    public Stream stream()
    {
        if (backingStore != null && useCache && !isCacheLoaded)
        {
            loadFromStore();
        }
        // TODO If using backing store yet not caching, then this will fail
        return delegate.stream();
    }

    @Override
    public Stream parallelStream()
    {
        if (backingStore != null && useCache && !isCacheLoaded)
        {
            loadFromStore();
        }
        // TODO If using backing store yet not caching, then this will fail
        return delegate.parallelStream();
    }
}