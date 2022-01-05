package com.tencent.matrix.lifecycle.supervisor

import android.app.Application
import android.content.Context
import android.transition.Scene
import com.tencent.matrix.lifecycle.IStateObserver
import com.tencent.matrix.lifecycle.IStateful
import com.tencent.matrix.lifecycle.MultiSourceStatefulOwner
import com.tencent.matrix.lifecycle.StatefulOwner
import com.tencent.matrix.lifecycle.owners.ExplicitBackgroundOwner
import com.tencent.matrix.util.MatrixHandlerThread
import com.tencent.matrix.util.MatrixLog
import com.tencent.matrix.util.safeApply
import java.util.concurrent.ConcurrentHashMap

/**
 * cross process StatefulOwner
 *
 * DispatcherStateOwner sends attached owner state changes to supervisor
 * Supervisor sync the app states back through [dispatchOn] and [dispatchOff]
 *
 * Created by Yves on 2021/12/2
 */
@Suppress("LeakingThis")
internal open class DispatcherStateOwner(
    reduceOperator: (stateful: Collection<IStateful>) -> Boolean,
    val attachedSource: StatefulOwner,
    val name: String
) : MultiSourceStatefulOwner(reduceOperator) {

    companion object {
        private val dispatchOwners = ConcurrentHashMap<String, DispatcherStateOwner>()

        @Volatile
        private var attached = false

        fun ownersToProcessTokens(context: Context) =
            dispatchOwners.values
                .map { ProcessToken.current(context, it.name, it.attachedSource.active()) }
                .toTypedArray()

        fun dispatchOn(name: String) {
            dispatchOwners[name]?.dispatchOn()
        }

        fun dispatchOff(name: String) {
            dispatchOwners[name]?.dispatchOff()
        }

        /**
         * call from supervisor
         */
        fun syncStates(supervisorToken: ProcessToken, scene: String) {
            if (!ProcessSupervisor.isSupervisor) {
                throw IllegalStateException("call forbidden")
            }
            dispatchOwners.forEach {
                val active = it.value.active()
                MatrixLog.i(ProcessSupervisor.tag, "syncStates: ${it.key} $active")
                ProcessSubordinate.manager.dispatchState(supervisorToken, scene, it.key, active)
            }
        }

        fun attach(application: Application) {
            if (attached) {
                return
            }
            attached = true
            dispatchOwners.forEach {
                it.value.attachedSource.observeForever(object : IStateObserver {
                    override fun on() {
                        MatrixLog.d(ProcessSupervisor.tag, "attached ${it.key} turned ON")
                        safeApply("${ProcessSupervisor.tag}.${it.key}") {
                            ProcessSupervisor.supervisorProxy?.onStateChanged(
                                ProcessToken.current(application, it.key, true)
                            )
                        }
                    }

                    override fun off() {
                        MatrixLog.d(ProcessSupervisor.tag, "attached ${it.key} turned OFF")
                        safeApply("${ProcessSupervisor.tag}.${it.key}") {
                            ProcessSupervisor.supervisorProxy?.onStateChanged(
                                ProcessToken.current(application, it.key, false)
                            )
                        }
                    }
                })

                if (it.value.attachedSource is ExplicitBackgroundOwner) {
                    it.value.attachedSource.observeForever(object : IStateObserver {
                        override fun on() {
                            safeApply(ProcessSupervisor.tag) {
                                ProcessSupervisor.supervisorProxy?.onProcessBackground(
                                    ProcessToken.current(application)
                                )
                            }
                        }

                        override fun off() {
                            safeApply(ProcessSupervisor.tag) {
                                ProcessSupervisor.supervisorProxy?.onProcessForeground(
                                    ProcessToken.current(application)
                                )
                            }
                        }
                    })
                }
            }
            MatrixLog.i(ProcessSupervisor.tag, "DispatcherStateOwners attached")
        }

        fun observe(observer: (stateName: String, state: Boolean) -> Unit) {
            dispatchOwners.forEach {
                it.value.observeForever(object : IStateObserver {
                    override fun on() {
                        observer.invoke(it.key, true)
                    }

                    override fun off() {
                        observer.invoke(it.key, false)
                    }
                })
            }
        }

        fun addSourceOwner(name: String, source: StatefulOwner) {
            dispatchOwners[name]?.addSourceOwner(source)
        }

        fun removeSourceOwner(name: String, source: StatefulOwner) {
            dispatchOwners[name]?.removeSourceOwner(source)
        }
    }

    init {
        dispatchOwners[name] = this
    }

    private val h = MatrixHandlerThread.getDefaultHandler()
    private fun dispatchOn() = h.post { turnOn() }
    private fun dispatchOff() = h.post { turnOff() }
}