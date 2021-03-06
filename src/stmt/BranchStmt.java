package stmt;

import java.util.LinkedList;
import java.util.List;

import compiler.Block;

import token.Code;
import token.Token;

// br, blbc, blbs
public class BranchStmt extends Stmt {
	
	private Block brBlock = null;

	public BranchStmt(int index, Operator op, List<Token> oprands) {
		super(index, op);
		rhs = oprands;
		lhs = new LinkedList();
	}
	
	// br
	public BranchStmt(Block brBlock) {
		super(Operator.br);
		this.rhs = new LinkedList();
		this.rhs.add(new Code(brBlock.body.get(0).index));
		this.lhs = new LinkedList();
		this.brBlock = brBlock;
	}
	
	// only for blbc & blbs
	public void flipOperator() {
		if (op == Operator.blbc)
			op = Operator.blbs;
		else if (op == Operator.blbs)
			op = Operator.blbc;
	}
	
	public void setBranchBlock(Block b) { brBlock = b; }
	
	public Block getBranchBlock() { return brBlock; } 
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(100);
		sb.append("    instr " + index + ": " + op);
		
		if (op == Operator.br) {
			sb.append(" [" + brBlock.body.get(0).index + "]");
		} else {
			sb.append(" " + rhs.get(0));
			sb.append(" [" + brBlock.body.get(0).index + "]");
		}
		
		return sb.toString();
	}
	
	@Override
	public String toIRString() {
		StringBuilder sb = new StringBuilder(100);
		sb.append("    instr " + index + ": " + op);
		
		if (op == Operator.br) {
			sb.append(" [" + brBlock.body.get(0).index + "]");
		} else {
			sb.append(" " + rhs.get(0).toIRString());
			sb.append(" [" + brBlock.body.get(0).index + "]");
		}

		return sb.toString();
	}
	
	@Override
	public String toSSAString() {
		StringBuilder sb = new StringBuilder(100);
		sb.append("    instr " + index + ": " + op);
		
		if (op == Operator.br) {
			sb.append(" [" + brBlock.body.get(0).index + "]");
		} else {
			sb.append(" " + rhs.get(0).toSSAString());
			sb.append(" [" + brBlock.body.get(0).index + "]");
		}

		return sb.toString();
	}
}
