package org.skepsun.kototoro.dex

import com.googlecode.dex2jar.ir.IrMethod
import com.googlecode.dex2jar.ir.expr.Local
import com.googlecode.dex2jar.ir.expr.Value
import com.googlecode.dex2jar.ir.stmt.AssignStmt
import com.googlecode.dex2jar.ir.stmt.LabelStmt
import com.googlecode.dex2jar.ir.stmt.Stmt
import com.googlecode.dex2jar.ir.stmt.StmtList
import com.googlecode.dex2jar.ir.ts.Cfg
import com.googlecode.dex2jar.ir.ts.StatedTransformer
import java.util.IdentityHashMap

/**
 * dex2jar 2.4.38 `RemoveLocalFromSSA` with a linear loop-phi step; everything else is a line-by-line port.
 *
 * The original finds, for every phi, all locals that reach it through phi chains by repeatedly adding whole ancestor
 * sets along a worklist. A method with thousands of phis (R8-merged parser registries, giant `when` tables) makes
 * that cubic: one 5.6 MB plugin DEX spent 88 % of a 29 s JIT conversion — minutes on the interpreter-only iOS JVM —
 * there. The step only asks whether exactly one non-phi local reaches a phi, so strongly connected components in
 * topological order with a two-element cap give the same replacements in linear time.
 */
internal class LinearRemoveLocalFromSSA : StatedTransformer() {

    override fun transformReportChanged(method: IrMethod): Boolean {
        var irChanged = false
        val assignStmtList = ArrayList<AssignStmt>()
        val phiLabels = method.phiLabels
        var p: Stmt? = method.stmts.first
        while (p != null) {
            if (p.st == Stmt.ST.ASSIGN) {
                val assign = p as AssignStmt
                if (assign.op1.vt == Value.VT.LOCAL && assign.op2.vt == Value.VT.LOCAL) assignStmtList += assign
            }
            p = p.next
        }
        val toReplace = HashMap<Local, Local>()
        val set = HashSet<Value>()
        var changed = true
        while (changed) {
            changed = false
            if (removeLoopFromPhi(phiLabels, toReplace)) {
                fixReplace(toReplace)
                replacePhi(phiLabels, toReplace, set)
            }
            while (simplePhi(phiLabels, toReplace, set)) {
                fixReplace(toReplace)
                replacePhi(phiLabels, toReplace, set)
            }
            while (simpleAssign(phiLabels, assignStmtList, toReplace, method.stmts)) {
                fixReplace(toReplace)
                replaceAssign(assignStmtList, toReplace)
                changed = true
                irChanged = true
            }
            replacePhi(phiLabels, toReplace, set)
        }
        for (local in toReplace.keys) {
            method.locals.remove(local)
            irChanged = true
        }
        if (toReplace.isNotEmpty()) {
            Cfg.travelMod(method.stmts, object : Cfg.TravelCallBack {
                override fun onAssign(v: Local, `as`: AssignStmt): Value = v
                override fun onUse(v: Local): Value = toReplace[v] ?: v
            }, true)
        }
        return irChanged
    }

    private fun replaceAssign(assignStmtList: List<AssignStmt>, toReplace: Map<Local, Local>) {
        for (assign in assignStmtList) {
            val to = toReplace[assign.op2]
            if (to != null) assign.op2 = to
        }
    }

    private fun simpleAssign(
        phiLabels: List<LabelStmt>?, assignStmtList: MutableList<AssignStmt>, toReplace: MutableMap<Local, Local>,
        stmts: StmtList,
    ): Boolean {
        val usedInPhi = HashSet<Value>()
        if (phiLabels != null) {
            for (labelStmt in phiLabels) for (phi in labelStmt.phis) usedInPhi.addAll(phi.op2.ops)
        }
        var changed = false
        val iterator = assignStmtList.iterator()
        while (iterator.hasNext()) {
            val assign = iterator.next()
            if (assign.op1 !in usedInPhi) {
                iterator.remove()
                stmts.remove(assign)
                toReplace[assign.op1 as Local] = assign.op2 as Local
                changed = true
            }
        }
        return changed
    }

    private fun replacePhi(phiLabels: List<LabelStmt>?, toReplace: Map<Local, Local>, set: MutableSet<Value>) {
        if (phiLabels == null) return
        for (labelStmt in phiLabels) {
            for (phi in labelStmt.phis) {
                for (op in phi.op2.ops) set.add(toReplace[op] ?: op)
                set.remove(phi.op1)
                phi.op2.ops = set.toTypedArray()
                set.clear()
            }
        }
    }

    private fun simplePhi(phiLabels: MutableList<LabelStmt>?, toReplace: MutableMap<Local, Local>, set: MutableSet<Value>): Boolean {
        var changed = false
        if (phiLabels == null) return false
        val labels = phiLabels.iterator()
        while (labels.hasNext()) {
            val labelStmt = labels.next()
            val phis = labelStmt.phis.iterator()
            while (phis.hasNext()) {
                val phi = phis.next()
                set.addAll(phi.op2.ops)
                set.remove(phi.op1)
                if (set.size == 1) {
                    phis.remove()
                    changed = true
                    toReplace[phi.op1 as Local] = set.iterator().next() as Local
                }
                set.clear()
            }
            if (labelStmt.phis.isEmpty()) {
                labelStmt.phis = null
                labels.remove()
            }
        }
        return changed
    }

    /** A local in the phi graph; edges point from a phi operand to the phi's result. */
    private class Node(val local: Local) {
        val children = ArrayList<Node>()
        var isInitByPhi = false
        var index = -1
        var lowLink = 0
        var onStack = false
        var component: Component? = null
    }

    /** Non-phi locals reaching a component, capped: [many] once two distinct ones are known. */
    private class Component {
        var single: Local? = null
        var many = false

        fun add(local: Local) {
            if (many || single === local) return
            if (single == null) single = local else many = true
        }

        fun addAll(other: Component) {
            if (other.many) many = true else other.single?.let(::add)
        }
    }

    private fun removeLoopFromPhi(phiLabels: MutableList<LabelStmt>?, toReplace: MutableMap<Local, Local>): Boolean {
        if (phiLabels == null) return false
        // Local has identity equality, so identity maps keep dex2jar's semantics without hashing the IR.
        val nodes = IdentityHashMap<Local, Node>()
        fun node(local: Local) = nodes.getOrPut(local) { Node(local) }
        for (labelStmt in phiLabels) {
            for (assign in labelStmt.phis) {
                val local = assign.op1 as Local
                val child = node(local)
                child.isInitByPhi = true
                for (op in assign.op2.ops) {
                    if (op === local) continue
                    val parent = node(op as Local)
                    // dex2jar links through sets; a repeated operand is one edge.
                    if (child !in parent.children) parent.children += child
                }
            }
        }
        // Tarjan emits a component after every component reachable from it, so the reverse order is topological.
        val order = stronglyConnected(nodes.values)
        val parentsOf = IdentityHashMap<Component, MutableList<Node>>()
        for (node in nodes.values) {
            for (child in node.children) {
                if (child.component !== node.component) parentsOf.getOrPut(child.component!!) { ArrayList() } += node
            }
        }
        for (i in order.indices.reversed()) {
            val component = order[i]
            for (parent in parentsOf[component].orEmpty()) {
                if (!parent.isInitByPhi) component.add(parent.local)
                component.addAll(parent.component!!)
            }
        }
        val toDeletePhiAssign = HashSet<Local>()
        var changed = false
        for (node in nodes.values) {
            if (!node.isInitByPhi) continue
            val component = node.component!!
            val local = component.single
            if (!component.many && local != null) {
                toReplace[node.local] = local
                toDeletePhiAssign += node.local
                changed = true
            }
        }
        val labels = phiLabels.iterator()
        while (labels.hasNext()) {
            val labelStmt = labels.next()
            labelStmt.phis.removeIf { it.op1 in toDeletePhiAssign }
            if (labelStmt.phis.isEmpty()) {
                labelStmt.phis = null
                labels.remove()
            }
        }
        return changed
    }

    /** Iterative Tarjan: the iOS interpreter thread stack cannot take recursion over thousands of phis. */
    private fun stronglyConnected(nodes: Collection<Node>): List<Component> {
        val components = ArrayList<Component>()
        val stack = ArrayList<Node>()
        val work = ArrayList<Pair<Node, Int>>()
        var next = 0
        for (root in nodes) {
            if (root.index >= 0) continue
            work += root to 0
            while (work.isNotEmpty()) {
                val (node, edge) = work.removeAt(work.lastIndex)
                if (edge == 0) {
                    node.index = next
                    node.lowLink = next
                    next++
                    stack += node
                    node.onStack = true
                } else {
                    val child = node.children[edge - 1]
                    node.lowLink = minOf(node.lowLink, child.lowLink)
                }
                var descended = false
                for (i in edge until node.children.size) {
                    val child = node.children[i]
                    if (child.index < 0) {
                        work += node to i + 1
                        work += child to 0
                        descended = true
                        break
                    } else if (child.onStack) {
                        node.lowLink = minOf(node.lowLink, child.index)
                    }
                }
                if (descended || node.lowLink != node.index) continue
                val component = Component()
                do {
                    val member = stack.removeAt(stack.lastIndex)
                    member.onStack = false
                    member.component = component
                } while (member !== node)
                components += component
            }
        }
        return components
    }

    private fun fixReplace(toReplace: MutableMap<Local, Local>) {
        val entries = ArrayList(toReplace.entries)
        entries.sortBy { it.key.lsIndex }
        var changed = true
        while (changed) {
            changed = false
            for (entry in entries) {
                val n = toReplace[entry.value]
                if (n != null && entry.value !== n) {
                    changed = true
                    entry.setValue(n)
                }
            }
        }
    }
}
