package org.apache.maven.plugins.release.config;

/*
 * Copyright 2005-2006 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Test stub for the release configuration store that holds a single configuration in memory.
 *
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 */
public class ReleaseConfigurationStoreStub
    implements ReleaseConfigurationStore
{
    /**
     * The release configuration to use.
     */
    private ReleaseConfiguration releaseConfiguration = new ReleaseConfiguration();

    public ReleaseConfiguration read( ReleaseConfiguration mergeConfiguration )
    {
        releaseConfiguration.merge( mergeConfiguration );
        return releaseConfiguration;
    }

    public void write( ReleaseConfiguration config )
    {
        this.releaseConfiguration = config;
    }

    public ReleaseConfiguration getReleaseConfiguration()
    {
        return releaseConfiguration;
    }
}
