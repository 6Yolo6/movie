package com.gying.movie.utils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ObfuscatedTitleMatcherTest {
 @Test void alignsMixedChinesePinyinSymbolsAndTraditionalCharacters() {
  assertTrue(ObfuscatedTitleMatcher.matches("一个–部门的DS (2026)", "一个部门的诞生"));
  assertTrue(ObfuscatedTitleMatcher.matches("黑-A-召-喚-師Z地-下-戀-愛-中", "黑暗召唤师在地下恋爱中"));
  assertTrue(ObfuscatedTitleMatcher.matches("H 黑-A-召-喚-師Z地-下-戀-愛-中 1080P", "黑暗召唤师在地下恋爱中"));
  assertTrue(ObfuscatedTitleMatcher.matches("一个部门的dan-sheng (2026)", "一个部门的诞生"));
  assertTrue(ObfuscatedTitleMatcher.matches("黑-日音-召唤师在地下恋爱中", "黑暗召唤师在地下恋爱中"));
 }
 @Test void requiresFullIdentityAndEnoughLiteralAnchors() {
  for(String value : new String[]{"YG BMD DS", "一个部门的DS2", "一个部门的DS 合集", "一个部门的谎言", "部门的DS", "一个部门的DS/另一部影片"})
   assertFalse(ObfuscatedTitleMatcher.matches(value,"一个部门的诞生"), value);
  assertFalse(ObfuscatedTitleMatcher.matches("ha召唤师zd下la中","黑暗召唤师在地下恋爱中"));
  assertFalse(ObfuscatedTitleMatcher.matches("A 黑-A-召-喚-師Z地-下-戀-愛-中","黑暗召唤师在地下恋爱中"));
 }
}
