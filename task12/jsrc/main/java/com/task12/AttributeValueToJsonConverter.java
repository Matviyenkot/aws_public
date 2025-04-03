package com.task12;

import java.util.LinkedHashMap;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;
import java.util.stream.Collectors;

public class AttributeValueToJsonConverter {


  public static Map<String, Object> convertAttributeValueMap(Map<String, AttributeValue> attributeValueMap) {
    return attributeValueMap.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> convertAttributeValue(entry.getValue()),
            (existing, replacement) -> existing,
            LinkedHashMap::new
        ));
  }

  private static Object convertAttributeValue(AttributeValue attributeValue) {
    if (attributeValue.s() != null) {
      return attributeValue.s();
    } else if (attributeValue.n() != null) {
      return Integer.valueOf(attributeValue.n()); // Convert number to Double
    } else if (attributeValue.bool() != null) {
      return attributeValue.bool();
    } else if (attributeValue.hasM()) {
      return convertAttributeValueMap(attributeValue.m());
    } else if (attributeValue.hasL()) {
      return attributeValue.l().stream()
          .map(AttributeValueToJsonConverter::convertAttributeValue)
          .collect(Collectors.toList());
    } else if (attributeValue.hasSs()) {
      return attributeValue.ss();
    } else if (attributeValue.hasNs()) {
      return attributeValue.ns().stream()
          .map(Double::valueOf)
          .collect(Collectors.toList());
    } else if (attributeValue.hasBs()) {
      return attributeValue.bs().stream()
          .map(b -> b.asUtf8String())
          .collect(Collectors.toList());
    } else if (attributeValue.nul() != null && attributeValue.nul()) {
      return null; // Handle null values
    }
    return null; // Default case for unsupported types
  }
}