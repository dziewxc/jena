/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hp.hpl.jena.tdb.transaction;

import com.hp.hpl.jena.query.ReadWrite ;
import com.hp.hpl.jena.sparql.JenaTransactionException ;
import com.hp.hpl.jena.tdb.StoreConnection ;
import com.hp.hpl.jena.tdb.base.file.Location ;
import com.hp.hpl.jena.tdb.migrate.DatasetGraphTrackActive ;
import com.hp.hpl.jena.tdb.store.DatasetGraphTDB ;

/** Transactional DatasetGraph that allows one active transaction.
 * For multiple read transactions, create multiple DatasetGraphTransaction objects.
 * This is analogous to a "connection" in JDBC.
 */

public class DatasetGraphTransaction extends DatasetGraphTrackActive
{
    /* Initially, the app can use this DatasetGraph non-transactionally.
     * But as soon as it starts a transaction, the dataset can only be used
     * inside transactions. 
     */

    static class ThreadLocalTxn extends ThreadLocal<DatasetGraphTxn>
    {
        // This is the default implementation - but nice to give it a name and to set it clearly.
        @Override protected DatasetGraphTxn initialValue() {
            return null ;
        }
    }
    
    static class ThreadLocalBoolean extends ThreadLocal<Boolean>
    {
        // This is the default implementation - but nice to give it a name and to set it clearly.
        @Override protected Boolean initialValue() {
            return Boolean.FALSE ;
        }
    }
    
    @Override
    protected void finalize() throws Throwable
    {
        txn.remove() ;
    }

    // Transaction per thread.
    private Object lock = new Object() ;
    private ThreadLocalTxn txn = new ThreadLocalTxn() ;
    private ThreadLocalBoolean inTransaction = new ThreadLocalBoolean() ;

    private boolean haveUsedInTransaction = false ;
    private final StoreConnection sConn ;

    public DatasetGraphTransaction(Location location)
    {
        sConn = StoreConnection.make(location) ;
    }

    public DatasetGraphTransaction(DatasetGraphTDB dsg)
    {
        sConn = StoreConnection.make(dsg) ;
    }

    public Location getLocation()       { return sConn.getLocation() ; }
    
    public DatasetGraphTDB getDatasetGraphToQuery()
    {
        return get() ;
    }
    
    public DatasetGraphTDB getBaseDatasetGraph()
    {
        return sConn.getBaseDataset() ;
    }

    @Override
    protected DatasetGraphTDB get()
    {
        if ( isInTransaction() )
        {
            DatasetGraphTxn dsgTxn = txn.get() ;
            if ( dsgTxn == null )
                throw new TDBTransactionException("In a transaction but no transactional DatasetGraph") ;
            return dsgTxn ;
        }
        
        if ( haveUsedInTransaction )
            throw new TDBTransactionException("Not in a transaction") ;

        // Never used in a transaction - return underlying database for old style (non-transactional) usage.  
        return sConn.getBaseDataset() ;
    }

    @Override
    protected void checkActive()
    {
        if ( haveUsedInTransaction && ! isInTransaction() )
            throw new JenaTransactionException("Not in a transaction ("+getLocation()+")") ;
    }

    @Override
    protected void checkNotActive()
    {
        if ( haveUsedInTransaction && isInTransaction() )
            throw new JenaTransactionException("Currently in a transaction ("+getLocation()+")") ;
    }
    
    @Override
    public boolean isInTransaction()    
    { return inTransaction.get() ; }

    /** This method sync the dataset if it has only ever been used non-transactionally.
     *  Otherwise it silently does nothing.
     */
    public void syncIfNotTransactional()    
    { 
        if ( ! haveUsedInTransaction )
            getBaseDatasetGraph().sync() ;
    }

    @Override
    protected void _begin(ReadWrite readWrite)
    {
        synchronized(lock)
        {
            if ( ! haveUsedInTransaction )
                getBaseDatasetGraph().sync() ;
            haveUsedInTransaction = true ;
            DatasetGraphTxn dsgTxn = sConn.begin(readWrite) ;
            txn.set(dsgTxn) ;
            inTransaction.set(true) ;
        }
    }

    @Override
    protected void _commit()
    {
        txn.get().commit() ;
        inTransaction.set(false) ;
    }

    @Override
    protected void _abort()
    {
        txn.get().abort() ;
        inTransaction.set(false) ;
    }

    @Override
    protected void _end()
    {
        txn.get().end() ;
        inTransaction.set(false) ;
        txn.set(null) ;
    }

    @Override
    public String toString()
    {
        // Risky ... 
        return get().toString() ;
    }
    
    
    @Override
    protected void _close()
    {
        if ( ! haveUsedInTransaction && get() != null )
            get().sync() ;
        // Don't close the base dataset.
//        if (get() != null)
//            get().close() ;
    }
}