package com.fesu.renjana.core

/**
 * Concrete manifest entry points for the isolated guest processes.
 *
 * Keep these class names in sync with AndroidManifest.xml (StubActivity_0..9).
 * They live in a separate source file so changes to the shared StubActivity do not
 * accidentally remove the components Android instantiates when launching a guest.
 */
class StubActivity_0 : StubActivity() {
    override fun getStubIndex(): Int = 0
}

class StubActivity_1 : StubActivity() {
    override fun getStubIndex(): Int = 1
}

class StubActivity_2 : StubActivity() {
    override fun getStubIndex(): Int = 2
}

class StubActivity_3 : StubActivity() {
    override fun getStubIndex(): Int = 3
}

class StubActivity_4 : StubActivity() {
    override fun getStubIndex(): Int = 4
}

class StubActivity_5 : StubActivity() {
    override fun getStubIndex(): Int = 5
}

class StubActivity_6 : StubActivity() {
    override fun getStubIndex(): Int = 6
}

class StubActivity_7 : StubActivity() {
    override fun getStubIndex(): Int = 7
}

class StubActivity_8 : StubActivity() {
    override fun getStubIndex(): Int = 8
}

class StubActivity_9 : StubActivity() {
    override fun getStubIndex(): Int = 9
}
