package compiler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import attr.ConstantAttr;
import attr.ConstantAttr.ConstantType;

import stmt.AllocStmt;
import stmt.ArithStmt;
import stmt.BranchStmt;
import stmt.DynamicStmt;
import stmt.EntryStmt;
import stmt.MemoryStmt;
import stmt.MoveStmt;
import stmt.ObjCmpStmt;
import stmt.PhiNode;
import stmt.SafetyStmt;
import stmt.Stmt;
import stmt.Stmt.Operator;
import token.Constant;
import token.GP;
import token.Offset;
import token.Register;
import token.Token;
import token.Variable;

public class ConstantPropOpt {
	private class FlowEdge {
		public Block src;
		public Block dst;
		
		public FlowEdge(Block src, Block dst) {
			this.src = src;
			this.dst = dst;
		}
	}

	private Routine routine;
	private DefUseAnalysis du;
	
	private HashSet<String> executeFlag = new HashSet<String>();
	private List<FlowEdge> flowWorkList = new LinkedList<FlowEdge>();
	private List<Stmt> ssaWorkList = new LinkedList<Stmt>();
	private HashMap<String, ConstantAttr> varAttr = new HashMap<String, ConstantAttr>();
	
	public int varCounter = 0;
	
	public ConstantPropOpt(Routine r) {
		this.routine = r;
		this.du = new DefUseAnalysis(r);
	}
	
	private String getFlowEdgeString(FlowEdge e) {
		if (e.src == null)
			return "b#" + "->" + "b#" + e.dst.getIndex();
		else
			return "b#" + e.src.getIndex() + "->" + "b#" + e.dst.getIndex(); 
	}
	
	private String getFlowEdgeString(Block src, Block dst) {
		if (src == null)
			return "b#" + "->" + "b#" + dst.getIndex();
		else
			return "b#" + src.getIndex() + "->" + "b#" + dst.getIndex();
	}
	
	private void insertFlowWorkList(Block src, Block dst) {
		flowWorkList.add(new FlowEdge(src, dst));
	}
	
	private void insertSSAWorkList(String var) {
		for (Stmt stmt: du.getUseList(var))
			ssaWorkList.add(stmt);
	}
	
	private ConstantAttr getTokenAttr(Token t) {
		if (t instanceof Variable || t instanceof Register)
			return varAttr.get(t.toSSAString());
		else if (t instanceof Constant)
			return new ConstantAttr(((Constant) t).getValue());
		else if (t instanceof Offset)
			return new ConstantAttr(((Offset) t).getValue());
		else if (t instanceof GP)
			return new ConstantAttr(ConstantType.Bottom);
		return null;
	}
	
	private void visitPhiNode(PhiNode phiNode) {
		
		List<Token> rhs = phiNode.getRHS();
		String name = phiNode.getLHS().get(0).toSSAString();
		ConstantAttr oldAttr = varAttr.get(name);
		ConstantAttr newAttr = ConstantAttr.operate(Stmt.Operator.phi, 
				getTokenAttr(rhs.get(0)),
				getTokenAttr(rhs.get(1)));
		
		for (int i = 2; i < rhs.size(); i++)
			newAttr = ConstantAttr.operate(Stmt.Operator.phi, newAttr, getTokenAttr(rhs.get(i)));
		
		if (!newAttr.equals(oldAttr)) {
			varAttr.put(name, newAttr);
			insertSSAWorkList(name);
		}
		
//		System.out.println(phiNode.toSSAString() + " old:" + oldAttr + ", new:" + newAttr);
	}
	
	private void visitArithStmt(ArithStmt stmt) {
		
		List<Token> rhs = stmt.getRHS();
		Token lhs = stmt.getLHS().get(0);
		String name = lhs.toSSAString();
		ConstantAttr newAttr;
		ConstantAttr oldAttr = getTokenAttr(lhs);
		
		Stmt.Operator op = stmt.getOperator();
		if (op == Stmt.Operator.neg)
			newAttr = ConstantAttr.operate(op, getTokenAttr(rhs.get(0)));
		else
			newAttr = ConstantAttr.operate(op, getTokenAttr(rhs.get(0)), getTokenAttr(rhs.get(1)));
		
		if (!newAttr.equals(oldAttr)) {
			varAttr.put(name, newAttr);
			insertSSAWorkList(name);
		}
		
//		System.out.println(stmt.toSSAString() + " old:" + oldAttr + ", new:" + newAttr);
	}
	
	private void visitMoveStmt(MoveStmt stmt) {
		Token rhs = stmt.getRHS().get(0);
		Token lhs = stmt.getLHS().get(0);
		
		ConstantAttr oldAttr = getTokenAttr(lhs);
		ConstantAttr newAttr = getTokenAttr(rhs);
		
		if (!newAttr.equals(oldAttr)) {
			varAttr.put(lhs.toSSAString(), newAttr);
			insertSSAWorkList(lhs.toSSAString());
		}
		
//		System.out.println(stmt.toSSAString() + " old:" + oldAttr + ", new:" + newAttr);
	}
	
	private void visitBranchStmt(BranchStmt stmt) {
		int brVal;
		
		if (stmt.getOperator() == Stmt.Operator.blbc)
			brVal = 0;
		else if (stmt.getOperator() == Stmt.Operator.blbs)
			brVal = 1;
		else
			return;
		
		Register r = (Register) stmt.getRHS().get(0);
		ConstantAttr attr = varAttr.get(r.toSSAString());
		Block b = stmt.getBlock();
		
		if (attr.type == ConstantAttr.ConstantType.Bottom) {
			insertFlowWorkList(b, b.getSuccs().get(0));
			insertFlowWorkList(b, b.getSuccs().get(1));
		} else if (attr.type == ConstantAttr.ConstantType.Constant) {
			Block dst = stmt.getBranchBlock();
			if (attr.value == brVal) {
				insertFlowWorkList(b, dst);
			} else {
				if (dst != b.getSuccs().get(0))
					insertFlowWorkList(b, b.getSuccs().get(0));
				else 
					insertFlowWorkList(b, b.getSuccs().get(1));
			}
		} else {
			System.out.println("ConstantPropOpt.visitBranch error: branch reg isn't initialized (" + stmt.toSSAString() + ")");
		}
	}
	
	private void visitOtherStmt(Stmt stmt) {
		if (stmt instanceof MemoryStmt) {
			if (stmt.getOperator() == Operator.load) {
				ConstantAttr attr = getTokenAttr(stmt.getLHS().get(0));
				attr.type = ConstantType.Bottom;
			}
		} else if (stmt instanceof DynamicStmt) {
			if (stmt.getOperator() == Operator.lddynamic) {
				ConstantAttr attr = getTokenAttr(stmt.getLHS().get(0));
				attr.type = ConstantType.Bottom;
			}
		} else if (stmt instanceof AllocStmt) {
			// alloc type on heap, it should be bottom
			ConstantAttr attr = getTokenAttr(stmt.getLHS().get(0));
			attr.type = ConstantType.Bottom;
		} else if (stmt instanceof ObjCmpStmt) {
			// type/null check, bottom
			ConstantAttr attr = getTokenAttr(stmt.getLHS().get(0));
			attr.type = ConstantType.Bottom;
		} else if (stmt instanceof SafetyStmt) {
			// safety check, bottom
			if (stmt.getLHS().size() > 0) {
				ConstantAttr attr = getTokenAttr(stmt.getLHS().get(0));
				attr.type = ConstantType.Bottom;
			}
		}
		// other stmt doesn't have lhs
	}
	
	private void visitStmt(Stmt stmt) {
		if (stmt instanceof ArithStmt) {
			visitArithStmt((ArithStmt) stmt);
		} else if (stmt instanceof MoveStmt) {
			visitMoveStmt((MoveStmt) stmt);
		} else if (stmt instanceof BranchStmt) {
			visitBranchStmt((BranchStmt) stmt);
		} else if (stmt instanceof PhiNode) {
			visitPhiNode((PhiNode) stmt);
		} else {
			visitOtherStmt(stmt);
		}
	}
	
	private void visitStmt(Block b) {
//		System.out.println("visit block#" + b.index);
		
		for (PhiNode phiNode: b.getPhiNode())
			visitPhiNode(phiNode);
		
		for (Stmt stmt: b.body)
			visitStmt(stmt);
	}
	
	private int reachCount(Block block) {
		int count = 0;
		for (Block pred: block.getPreds())
			if (executeFlag.contains(getFlowEdgeString(pred, block)))
				count ++;
		if (executeFlag.contains(getFlowEdgeString(null, block)))
			count ++;
		
		return count;
	}
	
	private boolean containsPhiNode(List<Stmt> use) {
		for (Stmt s: use)
			if (s instanceof PhiNode)
				return true;
		return false;
	}
	
	private void eliminateCode() {
		
		Iterator<Block> itBlock = routine.getBlocks().iterator();
		while (itBlock.hasNext()) {
			Block block = itBlock.next();
			int count = reachCount(block);
			if (count == 0) {
//				System.out.println("Remove block#" + block.index);
				for (Block pred: block.getPreds())
					pred.getSuccs().remove(block);
				for (Block succ: block.getSuccs()) {
					int i;
					for (i = 0; i < succ.getPreds().size(); i++)
						if (succ.getPreds().get(i) == block)
							break;
					Iterator<PhiNode> itPhi = succ.getPhiNode().iterator();
					while (itPhi.hasNext()) {
						PhiNode phi = itPhi.next();
						if (phi.removeRHS(i))
							itPhi.remove();
					}
					succ.getPreds().remove(block);
				}
				itBlock.remove();
			}
		}
		
		Set<String> keys = varAttr.keySet();
		
		for (String var: keys) {
			ConstantAttr attr = varAttr.get(var);
			
			if (attr.type == ConstantType.Constant) {
				
				varCounter ++;
				
				Stmt def = du.getDef(var);
				List<Stmt> use = du.getUseList(var);
				
				if (!containsPhiNode(use)) {
					def.getBlock().removeStmt(def);
					for (Stmt stmt: use) {
						if (stmt instanceof BranchStmt) {
							int brVal;
							
							if (stmt.getOperator() == Stmt.Operator.blbc)
								brVal = 0;
							else // blbs
								brVal = 1;
							
							if (attr.value == brVal) {
								stmt.getBlock().replaceStmt(stmt, new BranchStmt(((BranchStmt) stmt).getBranchBlock()));
							} else {
								stmt.getBlock().removeStmt(stmt);
							}
							
						} else {
							int i;
							for (i = 0; i < stmt.getRHS().size(); i++) {
								Token t = stmt.getRHS().get(i);
								if (t.toSSAString().equals(var))
									break;
							}
							stmt.setRHS(i, new Constant(attr.value));
						}
					}
				}
			} else if (attr.type == ConstantType.Top) {
				Stmt def = du.getDef(var);
				
				if (!(def instanceof EntryStmt))
					def.getBlock().removeStmt(def);
			}
		}
	}
	
	public void optimize() {
		
		du.analyze();
		
		Block entryBlock = routine.getEntryBlock();
		
		Set<String> varName = du.getAllVarName();
		for (String s: varName)
			varAttr.put(s, new ConstantAttr(ConstantAttr.ConstantType.Top));
		
		// set parameters bottom
		for (Token t: entryBlock.body.get(0).getLHS())
			if (((Variable) t).offset > 0) {
				ConstantAttr attr = varAttr.get(t.toSSAString());
				attr.type = ConstantAttr.ConstantType.Bottom;
			}
		
		FlowEdge entryEdge = new FlowEdge(null, entryBlock);
		flowWorkList.add(entryEdge);
		
		while (!flowWorkList.isEmpty() || !ssaWorkList.isEmpty()) {
			while (!flowWorkList.isEmpty()) {
				
				FlowEdge edge = flowWorkList.get(0);
				flowWorkList.remove(0);
				
				if (executeFlag.contains(getFlowEdgeString(edge)))
					continue;
				executeFlag.add(getFlowEdgeString(edge));
				
				Block block = edge.dst;
				
				int count = reachCount(block);
				if (count == 1)
					visitStmt(block);
				
				if (block.getSuccs().size() == 1)
					insertFlowWorkList(block, block.getSuccs().get(0));
				
			}
			
			while (!ssaWorkList.isEmpty()) {
				Stmt stmt = ssaWorkList.get(0);
				ssaWorkList.remove(0);
				
				Block block = stmt.getBlock();
				if (reachCount(block) > 0)
					visitStmt(stmt);
			}
		}
		
		eliminateCode();
	}
	
	public void dump() {
		routine.dumpSSA();
		Set<String> keys = varAttr.keySet();
		for (String key: keys)
			System.out.println(key + ": " + varAttr.get(key));
	}
}
