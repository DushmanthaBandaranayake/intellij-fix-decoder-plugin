package com.dush.intellij.fixdecoder.core.parse;

import com.dush.intellij.fixdecoder.core.dictionary.FieldDef;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Checks BodyLength (9), CheckSum (10) and enumerated values. Group checks are made while parsing. */
final class FixValidator {

    private FixValidator() {
    }

    static void validate(FixMessage m) {
        checkLengthAndChecksum(m);
        checkEnums(m);
    }

    private static void checkLengthAndChecksum(FixMessage m) {
        if (!m.isComplete() || m.hasUnknownParts()) return;
        List<RawField> fields = m.fields();
        if (fields.size() > 1 && fields.get(1).tag() == 9) {
            RawField nine = fields.get(1);
            int expected = bodyLength(fields);
            if (expected >= 0 && !String.valueOf(expected).equals(nine.value().trim())) {
                m.addProblem(new Problem(Problem.Kind.BODY_LENGTH, nine, nine.start(), nine.end(),
                        "BodyLength (9) should be " + expected, -1));
            }
        }
        RawField ten = checksumField(fields);
        if (ten != null) {
            String expected = checksum(fields, ten, null);
            if (!expected.equals(ten.value().trim())) {
                m.addProblem(new Problem(Problem.Kind.CHECKSUM, ten, ten.start(), ten.end(),
                        "CheckSum (10) should be " + expected, -1));
            }
        }
    }

    private static void checkEnums(FixMessage m) {
        for (RawField f : m.fields()) {
            FieldDef d = m.dictionary().field(f.tag());
            if (d == null || !d.isEnumerated() || f.value().isEmpty() || f.value().indexOf(FixScanner.UNKNOWN) >= 0) continue;
            if (d.meaning(f.value()) == null) {
                m.addProblem(new Problem(Problem.Kind.ENUM_VALUE, f, f.start(), f.end(),
                        "'" + f.value() + "' is not a valid value for " + FixParser.describe(d), -1));
            }
        }
    }

    /** The CheckSum field when it is the last field of a complete message, else null. */
    static RawField checksumField(List<RawField> fields) {
        if (fields.isEmpty()) return null;
        RawField last = fields.get(fields.size() - 1);
        return last.tag() == 10 ? last : null;
    }

    /**
     * Length of everything after the BodyLength field up to and including the delimiter before CheckSum,
     * counting each delimiter as one byte (SOH).
     */
    static int bodyLength(List<RawField> fields) {
        int firstBody = fields.size() > 1 && fields.get(1).tag() == 9 ? 2 : 1;
        int n = 0;
        for (int i = firstBody; i < fields.size(); i++) {
            RawField f = fields.get(i);
            if (f.tag() == 10) break;
            n += fieldBytes(f.tag(), f.value()) + 1;
        }
        return n;
    }

    /**
     * Sum of all bytes before CheckSum modulo 256, three digits. Each delimiter counts as SOH.
     * {@code nineValue} replaces the BodyLength value when not null.
     */
    static String checksum(List<RawField> fields, RawField ten, String nineValue) {
        int sum = 0;
        for (RawField f : fields) {
            if (f == ten) break;
            String value = f.tag() == 9 && nineValue != null ? nineValue : f.value();
            for (byte b : (f.tag() + "=" + value).getBytes(StandardCharsets.UTF_8)) sum += b & 0xFF;
            sum += 1;
        }
        return String.format("%03d", sum % 256);
    }

    private static int fieldBytes(int tag, String value) {
        return (tag + "=" + value).getBytes(StandardCharsets.UTF_8).length;
    }
}
