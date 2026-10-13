package kr.iroum.inspection.auth.password;

import java.util.regex.Pattern;

/**
 * 관리자 비밀번호 규칙(D-22): 8자 이상, 영문·숫자·ASCII 문장부호를 각각 1개 이상.
 * 공백·한글은 기호로 세지 않으며 앞뒤 공백을 임의로 제거하지 않는다. 임시 비밀번호에도 같은 규칙을 쓴다.
 */
public final class PasswordPolicy {

    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SYMBOL = Pattern.compile("[!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~]");

    private PasswordPolicy() {
    }

    public static boolean satisfiedBy(String password) {
        return password != null && password.length() >= 8 && LETTER.matcher(password).find() && DIGIT.matcher(password).find()
                && SYMBOL.matcher(password).find();
    }
}
