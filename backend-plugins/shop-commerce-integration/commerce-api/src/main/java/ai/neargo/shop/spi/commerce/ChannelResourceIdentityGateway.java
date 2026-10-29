package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;
import ai.neargo.shop.commerce.dto.MappingObjectTypeDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/** 仅查询原平台对象及父身份；不允许公共请求自报核验成功，不产生平台写操作。 */
public interface ChannelResourceIdentityGateway extends CommerceGateway {
    Proof query(Query query);

    enum ObjectType {
        MENU,
        CATEGORY,
        OFFER,
        SKU,
        OPTION_GROUP,
        OPTION,
        BUNDLE_GROUP,
        BUNDLE_MEMBER,
        WAREHOUSE,
        INVENTORY_POLICY
    }

    enum Status {
        VERIFIED,
        UNKNOWN,
        UNSUPPORTED,
        NOT_FOUND
    }

    record Parent(
            @JsonDeserialize(using = MappingObjectTypeDeserializer.class) ObjectType objectType,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalRef) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown parent identity field");
        }
    }

    record Identity(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalRef,
            List<Parent> parents) {
        public Identity {
            if (parents != null) parents = List.copyOf(parents);
        }

        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown resource identity field");
        }
    }

    record Query(
            String operationNo,
            String entityNo,
            String storeNo,
            String connectionNo,
            String profileNo,
            String externalAccountRef,
            String externalStoreRef,
            String authorizationNo,
            String credentialRef,
            ObjectType objectType,
            Identity identity) {}

    record Proof(
            Status status,
            String entityNo,
            String storeNo,
            String connectionNo,
            ObjectType objectType,
            Identity identity,
            String evidenceRef,
            Instant validUntil) {}
}
