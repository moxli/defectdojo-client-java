// SPDX-FileCopyrightText: the secureCodeBox authors
//
// SPDX-License-Identifier: Apache-2.0

package io.securecodebox.persistence.defectdojo.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import io.securecodebox.persistence.defectdojo.config.Config;
import io.securecodebox.persistence.defectdojo.exception.LoopException;
import io.securecodebox.persistence.defectdojo.http.Foo;
import io.securecodebox.persistence.defectdojo.http.ProxyConfigFactory;
import io.securecodebox.persistence.defectdojo.model.BaseModel;
import io.securecodebox.persistence.defectdojo.model.Engagement;
import io.securecodebox.persistence.defectdojo.model.Response;
import lombok.Getter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

// FIXME: Should be package private bc implementation detail.
abstract public class GenericDefectDojoService<T extends BaseModel> {
    protected Config config;

    protected ObjectMapper objectMapper;
    protected ObjectMapper searchStringMapper;

    @Getter
    protected RestTemplate restTemplate;

    public GenericDefectDojoService(Config config) {
        this.config = config;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.coercionConfigFor(Engagement.Status.class).setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull);
        this.objectMapper.findAndRegisterModules();

        this.searchStringMapper = new ObjectMapper();
        this.searchStringMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.searchStringMapper.coercionConfigFor(Engagement.Status.class).setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull);
        this.searchStringMapper.setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

        this.restTemplate = this.setupRestTemplate();
    }


    protected long DEFECT_DOJO_OBJET_LIMIT = 100L;

    /**
     * @return The DefectDojo Authentication Header
     */
    private HttpHeaders getDefectDojoAuthorizationHeaders() {
        return new Foo(config, new ProxyConfigFactory().create()).generateAuthorizationHeaders();
    }

    private RestTemplate setupRestTemplate() {
        RestTemplate restTemplate = new Foo(config, new ProxyConfigFactory().create()).createRestTemplate();
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(this.objectMapper);
        restTemplate.setMessageConverters(List.of(
                new FormHttpMessageConverter(),
                new ResourceHttpMessageConverter(),
                new StringHttpMessageConverter(),
                converter
        ));
        return restTemplate;
    }

    protected abstract String getUrlPath();

    protected abstract Class<T> getModelClass();

    protected abstract Response<T> deserializeList(String response) throws JsonProcessingException;

    public T get(long id) {
        var restTemplate = this.getRestTemplate();
        HttpEntity<String> payload = new HttpEntity<>(getDefectDojoAuthorizationHeaders());

        ResponseEntity<T> response = restTemplate.exchange(
                this.config.getUrl() + "/api/v2/" + this.getUrlPath() + "/" + id,
                HttpMethod.GET,
                payload,
                getModelClass()
        );

        return response.getBody();
    }

    protected Response<T> internalSearch(Map<String, Object> queryParams, long limit, long offset) throws JsonProcessingException, URISyntaxException {
        var restTemplate = this.getRestTemplate();
        HttpEntity<String> payload = new HttpEntity<>(getDefectDojoAuthorizationHeaders());

        var mutableQueryParams = new HashMap<String, Object>(queryParams);

        mutableQueryParams.put("limit", String.valueOf(limit));
        mutableQueryParams.put("offset", String.valueOf(offset));

        var multiValueMap = new LinkedMultiValueMap<String, String>();
        for (var entry : mutableQueryParams.entrySet()) {
            multiValueMap.set(entry.getKey(), String.valueOf(entry.getValue()));
        }

        var url = new URI(this.config.getUrl() + "/api/v2/" + this.getUrlPath() + "/");
        var uriBuilder = UriComponentsBuilder.fromUri(url).queryParams(multiValueMap);

        ResponseEntity<String> responseString = restTemplate.exchange(
                uriBuilder.build(mutableQueryParams),
                HttpMethod.GET,
                payload,
                String.class
        );

        return deserializeList(responseString.getBody());
    }

    public List<T> search(Map<String, Object> queryParams) throws URISyntaxException, JsonProcessingException {
        List<T> objects = new LinkedList<>();

        boolean hasNext = false;
        long page = 0;
        do {
            var response = internalSearch(queryParams, DEFECT_DOJO_OBJET_LIMIT, DEFECT_DOJO_OBJET_LIMIT * page++);
            objects.addAll(response.getResults());

            hasNext = response.getNext() != null;
            if (page > this.config.getMaxPageCountForGets()) {
                throw new LoopException("Found too many response object. Quitting after " + (page - 1) + " paginated API pages of " + DEFECT_DOJO_OBJET_LIMIT + " each.");
            }
        } while (hasNext);

        return objects;
    }

    public List<T> search() throws URISyntaxException, JsonProcessingException {
        return search(new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    public Optional<T> searchUnique(T searchObject) throws URISyntaxException, JsonProcessingException {
        Map<String, Object> queryParams = searchStringMapper.convertValue(searchObject, Map.class);

        var objects = search(queryParams);

        return objects.stream()
                .filter((object) -> object != null && object.equalsQueryString(queryParams))
                .findFirst();
    }

    public Optional<T> searchUnique(Map<String, Object> queryParams) throws URISyntaxException, JsonProcessingException {
        var objects = search(queryParams);

        return objects.stream()
                .filter((object) -> object.equalsQueryString(queryParams))
                .findFirst();
    }

    public T create(T object) {
        var restTemplate = this.getRestTemplate();
        HttpEntity<T> payload = new HttpEntity<T>(object, getDefectDojoAuthorizationHeaders());

        ResponseEntity<T> response = restTemplate.exchange(this.config.getUrl() + "/api/v2/" + getUrlPath() + "/", HttpMethod.POST, payload, getModelClass());
        return response.getBody();
    }

    public void delete(long id) {
        var restTemplate = this.getRestTemplate();
        HttpEntity<String> payload = new HttpEntity<>(getDefectDojoAuthorizationHeaders());

        restTemplate.exchange(this.config.getUrl() + "/api/v2/" + getUrlPath() + "/" + id + "/", HttpMethod.DELETE, payload, String.class);
    }

    public T update(T object, long objectId) {
        var restTemplate = this.getRestTemplate();
        HttpEntity<T> payload = new HttpEntity<T>(object, getDefectDojoAuthorizationHeaders());

        ResponseEntity<T> response = restTemplate.exchange(this.config.getUrl() + "/api/v2/" + getUrlPath() + "/" + objectId + "/", HttpMethod.PUT, payload, getModelClass());
        return response.getBody();
    }
}
