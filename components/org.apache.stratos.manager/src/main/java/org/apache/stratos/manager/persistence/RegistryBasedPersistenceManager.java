/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.stratos.manager.persistence;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.manager.deploy.service.Service;
import org.apache.stratos.manager.exception.PersistenceManagerException;
import org.apache.stratos.manager.registry.RegistryManager;
import org.apache.stratos.manager.subscription.CartridgeSubscription;
import org.apache.stratos.manager.utils.Deserializer;
import org.apache.stratos.manager.utils.Serializer;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class RegistryBasedPersistenceManager extends PersistenceManager {

    private static final Log log = LogFactory.getLog(RegistryBasedPersistenceManager.class);
    // Registry paths
    private static final String STRATOS_MANAGER_REOSURCE = "/stratos_manager";
    private static final String ACTIVE_SUBSCRIPTIONS = "/subscriptions/active";
    private static final String INACTIVE_SUBSCRIPTIONS = "/subscriptions/inactive";
    private static final String SERVICES = "/services";

    @Override
    public void persistCartridgeSubscription (CartridgeSubscription cartridgeSubscription) throws PersistenceManagerException {

        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        if (tenantId != MultitenantConstants.SUPER_TENANT_ID) {
            // TODO: This is only a workaround. Proper fix is to write to tenant registry
            try {
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
                carbonContext.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
                carbonContext.setTenantId(MultitenantConstants.SUPER_TENANT_ID);

                persistSubscription(cartridgeSubscription);

            } finally {
                PrivilegedCarbonContext.endTenantFlow();
            }

        } else {
            persistSubscription(cartridgeSubscription);
        }
    }

    private void persistSubscription (CartridgeSubscription cartridgeSubscription) throws PersistenceManagerException {

        // persist
        try {
            RegistryManager.getInstance().persist(STRATOS_MANAGER_REOSURCE + ACTIVE_SUBSCRIPTIONS + "/" +
                    Integer.toString(cartridgeSubscription.getSubscriber().getTenantId()) + "/" +
                    cartridgeSubscription.getType() + "/" +
                    cartridgeSubscription.getAlias(), Serializer.serializeSubscriptionSontextToByteArray(cartridgeSubscription), cartridgeSubscription.getClusterDomain());

            if (log.isDebugEnabled()) {
                log.debug("Persisted CartridgeSubscription successfully: [ " + cartridgeSubscription.getSubscriber().getTenantDomain()
                        + ", " + cartridgeSubscription.getType() + ", " + cartridgeSubscription.getAlias() + " ] ");
            }

        } catch (RegistryException e) {
            throw new PersistenceManagerException(e);

        } catch (IOException e) {
            throw new PersistenceManagerException(e);
        }
    }

    @Override
    public void removeCartridgeSubscription (int tenantId, String type, String alias) throws PersistenceManagerException {

        if (tenantId != MultitenantConstants.SUPER_TENANT_ID) {
            // TODO: This is only a workaround. Proper fix is to write to tenant registry
            try {
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
                carbonContext.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
                carbonContext.setTenantId(MultitenantConstants.SUPER_TENANT_ID);

                removeSubscription(tenantId, type, alias);

            } finally {
                PrivilegedCarbonContext.endTenantFlow();
            }

        } else {
            removeSubscription(tenantId, type, alias);
        }
    }

    private void removeSubscription (int tenantId, String type, String alias) throws PersistenceManagerException {

        // move the subscription from active set to inactive set
        String sourcePath = STRATOS_MANAGER_REOSURCE + ACTIVE_SUBSCRIPTIONS + "/" + Integer.toString(tenantId) + "/" + type + "/" + alias;
        String targetPath = STRATOS_MANAGER_REOSURCE + INACTIVE_SUBSCRIPTIONS + "/" + Integer.toString(tenantId) + "/" + type + "/" + alias;

        try {
            RegistryManager.getInstance().move(sourcePath, targetPath);
            if (log.isDebugEnabled()) {
                log.debug("Moved CartridgeSubscription on " + sourcePath + " to " + targetPath + " successfully");
            }

        } catch (RegistryException e) {
            throw new PersistenceManagerException(e);
        }
    }

    @Override
    public Collection<CartridgeSubscription> getCartridgeSubscriptions () throws PersistenceManagerException {

        return traverseAndGetCartridgeSubscriptions(STRATOS_MANAGER_REOSURCE + ACTIVE_SUBSCRIPTIONS);
    }

    private Collection<CartridgeSubscription> traverseAndGetCartridgeSubscriptions (String resourcePath) throws PersistenceManagerException  {

        if (log.isDebugEnabled()) {
            log.debug("Root resource path: " + resourcePath);
        }

        Object resourceObj;

        try {
            resourceObj = RegistryManager.getInstance().retrieve(resourcePath);

        } catch (RegistryException e) {
            throw new PersistenceManagerException(e);
        }

        Collection<CartridgeSubscription> cartridgeSubscriptions = new ArrayList<CartridgeSubscription>();

        if (resourceObj == null) {
            // there is no resource at the given path
            return null;

        } else if (resourceObj instanceof String[]) {

            // get the paths for all SubscriptionContext instances
            String[] subscriptionResourcePaths = (String[]) resourceObj;
            if (log.isDebugEnabled()) {
                for (String retrievedResourcePath : subscriptionResourcePaths) {
                    log.debug("Retrieved resource sub-path " + retrievedResourcePath);
                }
            }

            // traverse the paths recursively
            for (String subscriptionResourcePath : subscriptionResourcePaths) {

                if (log.isDebugEnabled()) {
                    log.debug("Traversing resource path " + subscriptionResourcePath);
                }

                Collection<CartridgeSubscription> cartridgeSubscriptionSet = traverseAndGetCartridgeSubscriptions(subscriptionResourcePath);
				if (cartridgeSubscriptionSet != null) {
					cartridgeSubscriptions.addAll(cartridgeSubscriptionSet);
				}
            }

        } else {
            // De-serialize
            Object subscriptionObj;

            try {
                subscriptionObj = Deserializer.deserializeFromByteArray((byte[]) resourceObj);

            } catch (Exception e) {
                // issue might be de-serializing only this object, therefore log and continue without throwing
                log.error("Error while de-serializing the object retrieved from "  + resourcePath, e);
                return null;
            }

            if (subscriptionObj != null && subscriptionObj instanceof CartridgeSubscription) {

                CartridgeSubscription deserilizedCartridgeSubscription = (CartridgeSubscription) subscriptionObj;
                if (log.isDebugEnabled()) {
                    log.debug("Successfully de-serialized CartridgeSubscription: " + deserilizedCartridgeSubscription.toString());
                }

                //return Collections.singletonList(deserilizedCartridgeSubscription);
                cartridgeSubscriptions.add(deserilizedCartridgeSubscription);

            }
        }

        // remove any nulls
        cartridgeSubscriptions.removeAll(Collections.singleton(null));
        return cartridgeSubscriptions;
    }

    @Override
    public Collection<CartridgeSubscription> getCartridgeSubscriptions (int tenantId) throws PersistenceManagerException {

        return traverseAndGetCartridgeSubscriptions(STRATOS_MANAGER_REOSURCE + ACTIVE_SUBSCRIPTIONS + "/" + Integer.toString(tenantId));
    }

    @Override
    public void persistService(Service service) throws PersistenceManagerException {

        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        if (tenantId != MultitenantConstants.SUPER_TENANT_ID) {
            // TODO: This is only a workaround. Proper fix is to write to tenant registry
            try {
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
                carbonContext.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
                carbonContext.setTenantId(MultitenantConstants.SUPER_TENANT_ID);

                persistDeployedService(service);

            } finally {
                PrivilegedCarbonContext.endTenantFlow();
            }

        } else {
            persistDeployedService(service);
        }
    }

    private void persistDeployedService (Service service) throws PersistenceManagerException  {

        // persist Service
        try {
            RegistryManager.getInstance().persist(STRATOS_MANAGER_REOSURCE + SERVICES + "/" + service.getType(),
                    Serializer.serializeServiceToByteArray(service), null);

            if (log.isDebugEnabled()) {
                log.debug("Persisted Service successfully: [ " + service.getType() + ", " + service.getTenantRange() + " ]");
            }

        } catch (RegistryException e) {
            throw new PersistenceManagerException(e);

        } catch (IOException e) {
            throw new PersistenceManagerException(e);
        }
    }


    @Override
    public Collection<Service> getServices() throws PersistenceManagerException {

        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        if (tenantId != MultitenantConstants.SUPER_TENANT_ID) {
            // TODO: This is only a workaround. Proper fix is to write to tenant registry
            try {
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
                carbonContext.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
                carbonContext.setTenantId(MultitenantConstants.SUPER_TENANT_ID);

                return traverseAndGetDeloyedServices(STRATOS_MANAGER_REOSURCE + SERVICES);

            } finally {
                PrivilegedCarbonContext.endTenantFlow();
            }

        } else {
            return traverseAndGetDeloyedServices(STRATOS_MANAGER_REOSURCE + SERVICES);
        }
    }

    @Override
    public Service getService(String cartridgeType) throws PersistenceManagerException {

        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        if (tenantId != MultitenantConstants.SUPER_TENANT_ID) {
            // TODO: This is only a workaround. Proper fix is to write to tenant registry
            try {
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
                carbonContext.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
                carbonContext.setTenantId(MultitenantConstants.SUPER_TENANT_ID);

               return getDeployedService(cartridgeType);

            } finally {
                PrivilegedCarbonContext.endTenantFlow();
            }

        } else {
            return getDeployedService(cartridgeType);
        }
    }

    public Collection<Service> traverseAndGetDeloyedServices (String resourcePath) throws PersistenceManagerException {

        if (log.isDebugEnabled()) {
            log.debug("Root resource path: " + resourcePath);
        }

        Object resourceObj;

        try {
            resourceObj = RegistryManager.getInstance().retrieve(resourcePath);

        } catch (RegistryException e) {
            throw new PersistenceManagerException(e);
        }

        Collection<Service> services = new ArrayList<Service>();

        if (resourceObj == null) {
            // there is no resource at the given path
            return null;

        } else if (resourceObj instanceof String[]) {

            // get the paths for all Service instances
            String[] serviceResourcePaths = (String[]) resourceObj;
            if (log.isDebugEnabled()) {
                for (String retrievedResourcePath : serviceResourcePaths) {
                    log.debug("Retrieved resource sub-path " + retrievedResourcePath);
                }
            }

            // traverse the paths recursively
            for (String serviceResourcePath : serviceResourcePaths) {

                if (log.isDebugEnabled()) {
                    log.debug("Traversing resource path " + serviceResourcePath);
                }

                services.addAll(traverseAndGetDeloyedServices(serviceResourcePath));
            }

        } else {
            // De-serialize
            Object serviceObj;

            try {
                serviceObj = Deserializer.deserializeFromByteArray((byte[]) resourceObj);

            } catch (Exception e) {
                // issue might be de-serializing only this object, therefore log and continue without throwing
                log.error("Error while de-serializing the object retrieved from "  + resourcePath, e);
                return null;
            }

            if (serviceObj != null && serviceObj instanceof Service) {

                Service deserilizedService = (Service) serviceObj;
                if (log.isDebugEnabled()) {
                    log.debug("Successfully de-serialized Service: " + deserilizedService.toString());
                }

                services.add(deserilizedService);

            }
        }

        // remove any nulls
        services.removeAll(Collections.singleton(null));
        return services;
    }

    public Service getDeployedService (String cartridgeType) throws PersistenceManagerException {

        Object byteObj;

        try {
            byteObj = RegistryManager.getInstance().retrieve(STRATOS_MANAGER_REOSURCE + SERVICES + "/" + cartridgeType);

        } catch (RegistryException e) {
            throw new PersistenceManagerException(e);
        }

        if (byteObj == null) {
            return null;
        }

        Object serviceObj;

        try {
            serviceObj = Deserializer.deserializeFromByteArray((byte[]) byteObj);

        } catch (Exception e) {
            throw new PersistenceManagerException(e);
        }

        if (serviceObj instanceof Service) {
            return (Service) serviceObj;
        }

        return null;
    }

    @Override
    public void removeService(String cartridgeType) throws PersistenceManagerException {

        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        if (tenantId != MultitenantConstants.SUPER_TENANT_ID) {
            // TODO: This is only a workaround. Proper fix is to write to tenant registry
            try {
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
                carbonContext.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
                carbonContext.setTenantId(MultitenantConstants.SUPER_TENANT_ID);

                removeDeployedService(cartridgeType);

            } finally {
                PrivilegedCarbonContext.endTenantFlow();
            }

        } else {
            removeDeployedService(cartridgeType);
        }
    }

    private void removeDeployedService (String cartridgeType) throws PersistenceManagerException {

        String resourcePath = STRATOS_MANAGER_REOSURCE + SERVICES + "/" + cartridgeType;

        try {
            RegistryManager.getInstance().delete(resourcePath);
            if (log.isDebugEnabled()) {
                log.debug("Deleted Service on path " + resourcePath + " successfully");
            }

        } catch (RegistryException e) {
            throw new PersistenceManagerException(e);
        }
    }

}
