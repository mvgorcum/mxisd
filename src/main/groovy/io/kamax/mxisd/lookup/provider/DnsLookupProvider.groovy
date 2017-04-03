/*
 * mxisd - Matrix Identity Server Daemon
 * Copyright (C) 2017 Maxime Dor
 *
 * https://max.kamax.io/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.kamax.mxisd.lookup.provider

import io.kamax.mxisd.api.ThreePidType
import io.kamax.mxisd.config.ServerConfig
import io.kamax.mxisd.lookup.SingleLookupRequest
import io.kamax.mxisd.lookup.ThreePidMapping
import org.apache.commons.lang.StringUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.xbill.DNS.Lookup
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Type

import java.util.function.Function

@Component
class DnsLookupProvider extends RemoteIdentityServerProvider {

    private Logger log = LoggerFactory.getLogger(DnsLookupProvider.class)

    @Autowired
    private ServerConfig srvCfg

    @Override
    int getPriority() {
        return 10
    }

    String getSrvRecordName(String domain) {
        return "_matrix-identity._tcp." + domain
    }

    Optional<String> getDomain(String email) {
        int atIndex = email.lastIndexOf("@")
        if (atIndex == -1) {
            return Optional.empty()
        }

        return Optional.of(email.substring(atIndex + 1))
    }

    // TODO use caching mechanism
    Optional<String> findIdentityServerForDomain(String domain) {
        if (StringUtils.equals(srvCfg.getName(), domain)) {
            log.info("We are authoritative for {}, no remote lookup", domain)
            return Optional.empty()
        }

        log.info("Performing SRV lookup")
        String lookupDns = getSrvRecordName(domain)
        log.info("Lookup name: {}", lookupDns)

        SRVRecord[] records = (SRVRecord[]) new Lookup(lookupDns, Type.SRV).run()
        if (records != null) {
            Arrays.sort(records, new Comparator<SRVRecord>() {

                @Override
                int compare(SRVRecord o1, SRVRecord o2) {
                    return Integer.compare(o1.getPriority(), o2.getPriority())
                }

            })

            for (SRVRecord record : records) {
                log.info("Found SRV record: {}", record.toString())
                String baseUrl = "https://${record.getTarget().toString(true)}:${record.getPort()}"
                if (isUsableIdentityServer(baseUrl)) {
                    log.info("Found Identity Server for domain {} at {}", domain, baseUrl)
                    return Optional.of(baseUrl)
                } else {
                    log.info("{} is not a usable Identity Server", baseUrl)
                }
            }
        } else {
            log.info("No SRV record for {}", lookupDns)
        }

        log.info("Performing basic lookup using domain name {}", domain)
        String baseUrl = "https://" + domain
        if (isUsableIdentityServer(baseUrl)) {
            log.info("Found Identity Server for domain {} at {}", domain, baseUrl)
            return Optional.of(baseUrl)
        } else {
            log.info("{} is not a usable Identity Server", baseUrl)
            return Optional.empty()
        }
    }

    @Override
    Optional<?> find(SingleLookupRequest request) {
        log.info("Performing DNS lookup for {}", request.getThreePid())
        if (ThreePidType.email != request.getType()) {
            log.info("Skipping unsupported type {} for {}", request.getType(), request.getThreePid())
            return Optional.empty()
        }

        String domain = request.getThreePid().substring(request.getThreePid().lastIndexOf("@") + 1)
        log.info("Domain name for {}: {}", request.getThreePid(), domain)
        Optional<String> baseUrl = findIdentityServerForDomain(domain)

        if (baseUrl.isPresent()) {
            return performLookup(baseUrl.get(), request.getType().toString(), request.getThreePid())
        }

        return Optional.empty()
    }

    @Override
    List<ThreePidMapping> populate(List<ThreePidMapping> mappings) {
        List<ThreePidMapping> mappingsFound = new ArrayList<>()
        Map<String, List<ThreePidMapping>> domains = new HashMap<>()

        for (ThreePidMapping mapping : mappings) {
            if (!StringUtils.equals(mapping.getMedium(), ThreePidType.email.toString())) {
                log.info("Skipping unsupported type {} for {}", mapping.getMedium(), mapping.getValue())
                continue
            }

            Optional<String> domainOpt = getDomain(mapping.getValue())
            if (!domainOpt.isPresent()) {
                log.warn("No domain for 3PID {}", mapping.getValue())
                continue
            }

            String domain = domainOpt.get()
            List<ThreePidMapping> domainMappings = domains.computeIfAbsent(domain, new Function<String, List<ThreePidMapping>>() {

                @Override
                List<ThreePidMapping> apply(String s) {
                    return new ArrayList<>()
                }

            })
            domainMappings.add(mapping)
        }

        log.info("Looking mappings across {} domains", domains.keySet().size())
        for (String domain : domains.keySet()) {
            Optional<String> baseUrl = findIdentityServerForDomain(domain)
            if (!baseUrl.isPresent()) {
                log.info("No usable Identity server for domain {}", domain)
                continue
            }

            List<ThreePidMapping> domainMappings = find(baseUrl.get(), domains.get(domain))
            log.info("Found {} mappings in domain {}", domainMappings.size(), domain)
            mappingsFound.addAll(domainMappings)
        }

        log.info("Found {} mappings overall", mappingsFound.size())
        return mappingsFound
    }

}
