/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo.pool;

import io.questdb.Metrics;
import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.DdlListener;
import io.questdb.cairo.TableToken;
import io.questdb.cairo.wal.WalColFirstWriter;
import io.questdb.cairo.wal.WalDirectoryPolicy;
import io.questdb.cairo.wal.seq.TableSequencerAPI;

public class WalColFirstWriterPool extends AbstractMultiTenantPool<WalColFirstWriterPool.Tenant> {

    private final CairoEngine engine;

    public WalColFirstWriterPool(CairoConfiguration configuration, CairoEngine engine) {
        super(configuration, configuration.getWalWriterPoolMaxSegments(), configuration.getInactiveWalWriterTTL());
        this.engine = engine;
    }

    @Override
    protected byte getListenerSrc() {
        return PoolListener.SRC_WAL_COL_FIRST_WRITER;
    }

    @Override
    protected Tenant newTenant(TableToken tableToken, Entry<Tenant> entry, int index) {
        return new Tenant(
                this,
                entry,
                index,
                tableToken,
                engine.getTableSequencerAPI(),
                engine.getDdlListener(tableToken),
                engine.getWalDirectoryPolicy(),
                engine.getMetrics()
        );
    }

    public static class Tenant extends WalColFirstWriter implements PoolTenant<Tenant> {
        private final int index;
        private Entry<Tenant> entry;
        private AbstractMultiTenantPool<Tenant> pool;

        public Tenant(
                AbstractMultiTenantPool<Tenant> pool,
                Entry<Tenant> entry,
                int index,
                TableToken tableToken,
                TableSequencerAPI tableSequencerAPI,
                DdlListener ddlListener,
                WalDirectoryPolicy walDirectoryPolicy,
                Metrics metrics
        ) {
            super(pool.getConfiguration(), tableToken, tableSequencerAPI, ddlListener, walDirectoryPolicy, metrics);
            this.pool = pool;
            this.entry = entry;
            this.index = index;
        }

        @Override
        public void close() {
            if (isOpen()) {
                rollback();
                final AbstractMultiTenantPool<Tenant> pool = this.pool;
                if (pool != null && entry != null) {
                    if (!isDistressed()) {
                        if (pool.returnToPool(this)) {
                            return;
                        }
                    } else {
                        try {
                            super.close();
                        } finally {
                            pool.expelFromPool(this);
                        }
                        return;
                    }
                }
                super.close();
            }
        }

        @Override
        public Entry<Tenant> getEntry() {
            return entry;
        }

        @Override
        public int getIndex() {
            return index;
        }

        public void goodbye() {
            entry = null;
            pool = null;
        }

        @Override
        public void refresh() {
            try {
                goActive();
            } catch (Throwable ex) {
                close();
                throw ex;
            }
        }

        public void updateTableToken(TableToken ignoredTableToken) {
            // no-op: goActive will update table token
        }
    }
}