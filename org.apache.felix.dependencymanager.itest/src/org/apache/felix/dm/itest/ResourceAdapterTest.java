package org.apache.felix.dm.itest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import junit.framework.Assert;

import org.apache.felix.dm.DependencyManager;
import org.apache.felix.dm.ResourceHandler;
import org.apache.felix.dm.ResourceUtil;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

@SuppressWarnings({"deprecation", "unchecked", "rawtypes", "unused"})
public class ResourceAdapterTest extends TestBase {
    public void testBasicResourceAdapter() throws Exception {
        DependencyManager m = getDM();
        // helper class that ensures certain steps get executed in sequence
        Ensure e = new Ensure();
        // create a resource provider
        ResourceProvider provider = new ResourceProvider(e);
        // activate it
        m.add(m.createComponent().setImplementation(provider).add(m.createServiceDependency().setService(ResourceHandler.class).setCallbacks("add", "remove")));
        // create a resource adapter for our single resource
        // note that we can provide an actual implementation instance here because there will be only one
        // adapter, normally you'd want to specify a Class here
        m.add(m.createResourceAdapterService("(&(path=/path/to/*.txt)(host=localhost))", false, null, "changed")
              .setImplementation(new ResourceAdapter(e)));
        // wait until the single resource is available
        e.waitForStep(3, 5000);
        // trigger a 'change' in our resource
        provider.change();
        // wait until the changed callback is invoked
        e.waitForStep(4, 5000);
        m.clear();
     }
    
    static class ResourceAdapter {
        protected URL m_resource; // injected by reflection.
        private Ensure m_ensure;
        
        ResourceAdapter(Ensure e) {
            m_ensure = e;
        }
        
        public void start() {
            m_ensure.step(1);
            Assert.assertNotNull("resource not injected", m_resource);
            m_ensure.step(2);
            try {
                InputStream in = m_resource.openStream();
            } 
            catch (FileNotFoundException e) {
                m_ensure.step(3);
            }
            catch (IOException e) {
                Assert.fail("We should not have gotten this exception.");
            }
        }
        
        public void changed() {
            m_ensure.step(4);
        }
    }
    
    static class ResourceProvider {
        private volatile BundleContext m_context;
        private final Ensure m_ensure;
        private final Map m_handlers = new HashMap();
        private URL[] m_resources;

        public ResourceProvider(Ensure ensure) throws MalformedURLException {
            m_ensure = ensure;
            m_resources = new URL[] {
                new URL("file://localhost/path/to/file1.txt")
            };
        }
        
        public void change() {
            ResourceHandler[] handlers;
            synchronized (m_handlers) {
                handlers = (ResourceHandler[]) m_handlers.keySet().toArray(new ResourceHandler[m_handlers.size()]);
            }
            for (int i = 0; i < m_resources.length; i++) {
                for (int j = 0; j < handlers.length; j++) {
                    ResourceHandler handler = handlers[j];
                    handler.changed(m_resources[i]);
                }
            }
        }

        public void add(ServiceReference ref, ResourceHandler handler) {
            String filterString = (String) ref.getProperty("filter");
            Filter filter = null;
            if (filterString != null) {
                try {
                    filter = m_context.createFilter(filterString);
                }
                catch (InvalidSyntaxException e) {
                    Assert.fail("Could not create filter for resource handler: " + e);
                    return;
                }
            }
            synchronized (m_handlers) {
                m_handlers.put(handler, filter);
            }
            for (int i = 0; i < m_resources.length; i++) {
                if (filter == null || filter.match(ResourceUtil.createProperties(m_resources[i]))) {
                    handler.added(m_resources[i]);
                }
            }
        }

        public void remove(ServiceReference ref, ResourceHandler handler) {
            Filter filter;
            synchronized (m_handlers) {
                filter = (Filter) m_handlers.remove(handler);
            }
            removeResources(handler, filter);
        }

        private void removeResources(ResourceHandler handler, Filter filter) {
                for (int i = 0; i < m_resources.length; i++) {
                    if (filter == null || filter.match(ResourceUtil.createProperties(m_resources[i]))) {
                        handler.removed(m_resources[i]);
                    }
                }
            }

        public void destroy() {
            Entry[] handlers;
            synchronized (m_handlers) {
                handlers = (Entry[]) m_handlers.entrySet().toArray(new Entry[m_handlers.size()]);
            }
            for (int i = 0; i < handlers.length; i++) {
                removeResources((ResourceHandler) handlers[i].getKey(), (Filter) handlers[i].getValue());
            }
            
            System.out.println("DESTROY..." + m_handlers.size());
        }
    }
}
