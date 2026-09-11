package com.securetravels.crm.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneUtilsTest {

    @ParameterizedTest
    @CsvSource({
            "9876543210, 9876543210",
            "919876543210, 9876543210",
            "+91 98765 43210, 9876543210",
            "+91-98765-43210, 9876543210",
            "09876543210, 9876543210"
    })
    void normalizesIndianMobiles(String input, String expected) {
        assertThat(PhoneUtils.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "12345", "12345678901", "0123456789", "0987654321",
            "55512345", "+91987654321",
    })
    void rejectsInvalidMobiles(String input) {
        assertThat(PhoneUtils.normalize(input)).isNull();
    }

    @Test
    void rejectsNullOrBlank() {
        assertThat(PhoneUtils.normalize(null)).isNull();
        assertThat(PhoneUtils.normalize("  ")).isNull();
    }

    @Test
    void validatesMobileFormat() {
        assertThat(PhoneUtils.isValidMobile("+91 98765 43210")).isTrue();
        assertThat(PhoneUtils.isValidMobile("9876543210")).isTrue();
        assertThat(PhoneUtils.isValidMobile("12345")).isFalse();
        assertThat(PhoneUtils.isValidMobile(null)).isFalse();
    }
}