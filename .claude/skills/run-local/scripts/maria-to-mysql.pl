#!/usr/bin/perl
# MariaDB dump -> MySQL 9.7: collation + schema-unique CHECK names (per docs/technical/餐饮本地MySQL测试环境.md)
use strict; use warnings; use Digest::MD5 qw(md5_hex);
my $table = '';
while (my $l = <STDIN>) {
  next if $. == 1 && $l =~ /^\/\*M!999999/;
  $l =~ s/utf8mb4_uca1400_ai_ci/utf8mb4_unicode_520_ci/g;
  $table = $1 if $l =~ /^CREATE TABLE `([^`]+)`/;
  if ($l =~ /CONSTRAINT `([^`]+)` CHECK/) {
    my $n = $1; my $p = 'ck' . substr(md5_hex($table), 0, 8) . '_';
    my $new = substr($p . $n, 0, 64);
    $l =~ s/CONSTRAINT `\Q$n\E` CHECK/CONSTRAINT `$new` CHECK/;
  }
  print $l;
}
