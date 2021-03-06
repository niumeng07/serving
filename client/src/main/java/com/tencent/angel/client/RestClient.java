package com.tencent.angel.client;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestClient {

    private static final Logger LOG = LoggerFactory.getLogger(RestClient.class);

    public static void main(String[] args) {
        String getResource = "http://localhost:8501/angelServing/v1.0/models/default/versions/1";
        String postResource = "";
        Client client = Client.create();
        WebResource getWebResource = client
                .resource(getResource);
        WebResource postWebResource = client
                .resource(postResource);
        ClientResponse getResponse = getWebResource.accept("application/json")
                .get(ClientResponse.class);
        if (getResponse.getStatus() != 200) {
            throw new RuntimeException("Failed : HTTP error code : "
                    + getResponse.getStatus() + ", error message: "
                    + getResponse.getEntity(String.class));
        }
        LOG.info(getResponse.getEntity(String.class));
        LOG.info("status code: " + getResponse.getStatus());
        //String input = "";
        //ClientResponse postResponse = postWebResource.type("application/json")
        //        .post(ClientResponse.class, input);
    }

}
