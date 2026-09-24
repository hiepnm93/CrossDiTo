add_executable(CompanionProtocolTest
  CompanionProtocolTest.cpp
  ${REPO_ROOT}/src/companion/CompanionProtocol.cpp
)

target_include_directories(CompanionProtocolTest PRIVATE
  ${REPO_ROOT}/src/companion
)

target_link_libraries(CompanionProtocolTest PRIVATE
  crosspoint_test_common
  GTest::gtest_main
)

gtest_discover_tests(CompanionProtocolTest)
