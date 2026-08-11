package com.rag.webex.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebexMessage {
    private String id;
    private String roomId;
    private String roomType;
    private String text;
    private String personId;
    private String personEmail;
    private List<String> mentionedPeople;
    private String created;
}
